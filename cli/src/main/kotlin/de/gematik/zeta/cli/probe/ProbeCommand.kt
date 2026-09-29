package de.gematik.zeta.cli.probe

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import de.gematik.zeta.catalog.Environment
import de.gematik.zeta.catalog.ServiceCatalog
import de.gematik.zeta.catalog.ServiceDiscoveryClient
import de.gematik.zeta.cli.BuildConfig
import de.gematik.zeta.cli.client.ZetaSessionCommand
import de.gematik.zeta.cli.popp.poppServiceUrlFor
import de.gematik.zeta.cli.sdk.buildZetaSdkClient
import de.gematik.zeta.cli.state.ProfileStores
import de.gematik.zeta.cli.storage.ProfileDb
import de.gematik.zeta.cli.storage.ProfileDbCatalogStore
import de.gematik.zeta.cli.storage.zetaProfilePath
import de.gematik.zeta.cli.trace.Tracer
import de.gematik.zeta.sdk.SdkStatus
import de.gematik.zeta.sdk.storage.ResourceScope
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val log = KotlinLogging.logger {}

private val LIST_SPLIT = Regex("[,\\s]+")

/** How late a loop may wake up, or how far past its timeout a probe may run, before liveness fails. */
private val LIVENESS_GRACE = 60.seconds

/** `3h`, `5m`, `90s`, `1h30m`, ISO `PT3H`; `0` / `off` mean disabled. */
internal fun parseProbeDuration(raw: String): Duration {
    val v = raw.trim()
    if (v == "0" || v.equals("off", ignoreCase = true)) return Duration.ZERO
    val d = runCatching { Duration.parse(v) }
        .getOrElse { throw IllegalArgumentException("'$raw' is not a duration (use e.g. 30s, 5m, 3h, 1h30m, or 0/off)") }
    require(d >= Duration.ZERO) { "duration must not be negative: $raw" }
    return d
}

/**
 * `zeta probe` — a permanently running prober for one or more TI environments. Every target (each
 * env's catalog VSDM instances + PoPP service, plus explicit `--endpoint`s) gets a `login` probe (forced
 * full token exchange) and a `refresh` probe (forced refresh grant) on their own intervals, spread
 * round-robin so `n` targets on a `T` interval see one probe every `T/n`; failing targets back off
 * exponentially. Metrics and spans are pushed via OTLP; `/healthz` and `/readyz` serve an orchestrator.
 */
class ProbeCommand : ZetaSessionCommand(name = "probe") {
    private val envs: List<Environment> by option(
        "--probe-env",
        metavar = "ENVS",
        envvar = "ZETA_PROBE_ENV",
        help = "TI environment(s) to probe, comma-separated: dev (default), ref, test, prod. One auth identity " +
            "serves all of them; each target gets its env's catalog, PoPP service URL and ASL prod/non-prod. " +
            "(env: ZETA_PROBE_ENV)",
    ).convert { raw -> runCatching { parseEnvironments(raw) }.getOrElse { fail(it.message ?: "invalid environment") } }
        .default(listOf(Environment.DEV))

    private val endpointOpt: List<List<String>> by option(
        "--endpoint",
        metavar = "URL",
        envvar = "ZETA_PROBE_ENDPOINTS",
        help = "Additional Zeta-protected endpoint to probe (repeatable; the env var takes a whitespace- or " +
            "comma-separated list). Each one needs a matching --endpoint-scope; it belongs to the first --probe-env. " +
            "(env: ZETA_PROBE_ENDPOINTS)",
    ).split(LIST_SPLIT).multiple()

    private val endpointScopeOpt: List<List<String>> by option(
        "--endpoint-scope",
        metavar = "SCOPE",
        envvar = "ZETA_PROBE_ENDPOINT_SCOPES",
        help = "OAuth scope for the --endpoint at the same position (repeatable; same list form in the env " +
            "var). Counts must match. (env: ZETA_PROBE_ENDPOINT_SCOPES)",
    ).split(LIST_SPLIT).multiple()

    private val endpointNameOpt: List<List<String>> by option(
        "--endpoint-name",
        metavar = "SLUG",
        envvar = "ZETA_PROBE_ENDPOINT_NAMES",
        help = "Stable service slug for the --endpoint at the same position (repeatable; same list form in the " +
            "env var). Optional as a whole, but when given the count must match --endpoint. Default: the host name. " +
            "(env: ZETA_PROBE_ENDPOINT_NAMES)",
    ).split(LIST_SPLIT).multiple()

    private val endpointTypeOpt: List<List<String>> by option(
        "--endpoint-type",
        metavar = "TYPE",
        envvar = "ZETA_PROBE_ENDPOINT_TYPES",
        help = "Service type of the --endpoint at the same position, the key --type-label uses (repeatable; " +
            "same list form in the env var). Optional as a whole, but when given the count must match --endpoint. " +
            "Default: the endpoint's slug. (env: ZETA_PROBE_ENDPOINT_TYPES)",
    ).split(LIST_SPLIT).multiple()

    private val typeLabelOpt: List<List<String>> by option(
        "--type-label",
        metavar = "TYPE:KEY=VALUE",
        envvar = "ZETA_PROBE_TYPE_LABELS",
        help = "Extra label on every metric series and span of every target of service type TYPE (vsdm, popp, " +
            "or an --endpoint-type), e.g. vsdm:criticality=high (repeatable; the env var takes a whitespace- or " +
            "comma-separated list). KEY must be a Prometheus label name and none of env, service, type, endpoint, " +
            "probe, step, result, error_type, fallback. A type without targets is warned about, not refused. " +
            "(env: ZETA_PROBE_TYPE_LABELS)",
    ).split(LIST_SPLIT).multiple()

    private val noCatalog: Boolean by option(
        "--no-catalog",
        envvar = "ZETA_PROBE_NO_CATALOG",
        help = "Probe only the explicit --endpoint list; skip the env's service-discovery catalog and PoPP " +
            "service. (env: ZETA_PROBE_NO_CATALOG)",
    ).flag(default = false)

    private val poppServiceUrlOverride: String? by option(
        "--popp-service-url",
        metavar = "URL",
        envvar = "ZETA_POPP_SERVICE_URL",
        help = "PoPP service to probe; only with a single --probe-env. Defaults to the URL derived from the env. " +
            "(env: ZETA_POPP_SERVICE_URL)",
    )

    private val loginInterval: Duration by durationOption(
        "--login-interval", "ZETA_PROBE_LOGIN_INTERVAL",
        "How often each target gets a login probe (forced full SMC-B token exchange). 0/off disables. Default: 3h.",
    ).default(3.hours)

    private val refreshInterval: Duration by durationOption(
        "--refresh-interval", "ZETA_PROBE_REFRESH_INTERVAL",
        "How often each target gets a refresh probe (forced refresh grant). 0/off disables. Default: 5m.",
    ).default(5.minutes)

    private val catalogInterval: Duration by durationOption(
        "--catalog-interval", "ZETA_PROBE_CATALOG_INTERVAL",
        "How often the service-discovery catalog is re-read to pick up new or removed endpoints. 0/off disables. Default: 1h.",
    ).default(1.hours)

    private val backoffMax: Duration by durationOption(
        "--backoff-max", "ZETA_PROBE_BACKOFF_MAX",
        "Cap for the exponential hold-off of a failing target: after k consecutive failures its next probe of that " +
            "kind waits min(interval * 2^k, cap). 0/off disables. Default: 1h.",
    ).default(1.hours)

    private val probeTimeout: Duration by durationOption(
        "--probe-timeout", "ZETA_PROBE_TIMEOUT",
        "Upper bound for one probe run; a slower probe counts as a timeout. Default: 2m.",
    ).default(2.minutes)

    private val healthHost: String by option(
        "--health-host",
        metavar = "ADDR",
        envvar = "ZETA_PROBE_HEALTH_HOST",
        help = "Bind address of the health endpoints (/healthz, /readyz). Default: 0.0.0.0. (env: ZETA_PROBE_HEALTH_HOST)",
    ).default("0.0.0.0")

    private val healthPort: Int by option(
        "--health-port",
        metavar = "PORT",
        envvar = "ZETA_PROBE_HEALTH_PORT",
        help = "Port of the health endpoints; 0 disables them. Default: 8080. (env: ZETA_PROBE_HEALTH_PORT)",
    ).int().default(8080)

    private val otlpEndpoint: String? by option(
        "--otlp-endpoint",
        metavar = "URL",
        help = "OTLP/HTTP endpoint that metrics and traces are pushed to; required unless OTEL_EXPORTER_OTLP_ENDPOINT " +
            "(or both OTEL_EXPORTER_OTLP_METRICS_ENDPOINT and _TRACES_ENDPOINT) is set. OTEL_METRIC_EXPORT_INTERVAL " +
            "sets the push interval (default 30000 ms); every other OTEL_* variable (headers, certificates, mTLS, " +
            "compression, resource attributes) is honoured as-is.",
    )

    private val otlpHeaders: List<String> by option(
        "--otlp-header",
        metavar = "KEY=VALUE",
        help = "Header sent with every OTLP export, e.g. Authorization=Bearer … (repeatable). Appended to " +
            "OTEL_EXPORTER_OTLP_HEADERS.",
    ).multiple()

    private val otlpProtocol: String? by option(
        "--otlp-protocol",
        metavar = "PROTOCOL",
        help = "OTLP transport. Only http/protobuf is supported (the JDK HTTP sender has no gRPC). " +
            "Equivalent to OTEL_EXPORTER_OTLP_PROTOCOL.",
    ).choice(OTLP_PROTOCOL)

    override fun help(context: Context) =
        "Continuously probe the auth flows of one or more TI environments' endpoints; push metrics and traces via OTLP."

    private fun durationOption(name: String, envvar: String, help: String) =
        option(name, metavar = "DURATION", envvar = envvar, help = "$help (env: $envvar)")
            .convert { raw -> runCatching { parseProbeDuration(raw) }.getOrElse { fail(it.message ?: "invalid duration") } }

    private fun List<List<String>>.items(): List<String> = flatten().filter { it.isNotBlank() }

    override fun runCommand() {
        if (Tracer.enabled) {
            throw UsageError("--trace is not supported by zeta probe (its span tree would grow for the life of the process); use --otlp-endpoint")
        }
        if (probeTimeout <= Duration.ZERO) throw UsageError("--probe-timeout must be positive")
        val extras = pairEndpoints(endpointOpt.items(), endpointScopeOpt.items(), endpointNameOpt.items(), endpointTypeOpt.items())
        val typeLabels = parseTypeLabels(typeLabelOpt.items())
        if (loginInterval <= Duration.ZERO && refreshInterval <= Duration.ZERO) {
            throw UsageError("both --login-interval and --refresh-interval are off; nothing to probe")
        }
        if (poppServiceUrlOverride != null && envs.size > 1) throw UsageError("--popp-service-url needs exactly one --probe-env")
        if (healthPort !in 0..65535) throw UsageError("--health-port must be between 0 and 65535")
        val telemetryOptions = TelemetryOptions(envs.map { it.name.lowercase() }, otlpEndpoint, otlpHeaders, otlpProtocol)
        validateOtelConfig(telemetryOptions, ::otelEnvLookup)
        if (Environment.PROD in envs && envs.size > 1) {
            log.warn { "prod is probed alongside non-prod environments with the same identity, HTTP client and profile" }
        }

        val storagePath = zetaProfilePath(profile)
        val includeCatalog = !noCatalog
        fun poppUrlFor(env: Environment) = poppServiceUrlOverride ?: poppServiceUrlFor(env)
        val envList = envs.joinToString("+") { it.name.lowercase() }

        val registry = TargetRegistry(emptyList())
        val started = AtomicBoolean(false)
        var liveness: () -> List<String> = { emptyList() }
        val health = if (healthPort == 0) {
            null
        } else {
            ProbeHealthServer(
                healthHost,
                healthPort,
                notReadyReason = {
                    when {
                        !started.get() -> "starting"
                        registry.current.isEmpty() -> "no targets"
                        else -> null
                    }
                },
                livenessProblem = { liveness().takeIf { it.isNotEmpty() }?.joinToString("; ") },
            ).start()
        }

        val auth = buildTokenProvider()
        val (tokenProvider, connectorSession) = auth
        val signingLock = Mutex().takeIf { connectorSession != null }

        val backoff = BackoffPolicy(backoffMax) { kind -> if (kind == ProbeKind.LOGIN) loginInterval else refreshInterval }
        val (otel, effective) = buildOpenTelemetry(telemetryOptions, BuildConfig.VERSION)
        val telemetry = ProbeTelemetry(otel, { registry.current }, { backoff.entries() })

        val catalogStore = ProfileDbCatalogStore(ProfileDb(storagePath))
        val catalogs = ConcurrentHashMap<Environment, ServiceCatalog>()
        suspend fun fetchCatalog(env: Environment): ServiceCatalog? =
            runCatching { ServiceDiscoveryClient(cliConfig.httpClient, catalogStore).fetchCatalog(env) }
                .onSuccess { cachedCatalogFetchedAt(catalogStore, env)?.let { telemetry.recordCatalogFetchedAt(env, it) } }
                .onFailure { log.warn { "service-discovery catalog fetch failed for ${env.name.lowercase()}: ${it.message}" } }
                .getOrNull()
        fun buildTargetList(): List<ProbeTarget> = mergeEnvTargets(
            envs.map { env ->
                resolveTargets(env, catalogs[env], poppUrlFor(env), includeCatalog, if (env == envs.first()) extras else emptyList(), typeLabels)
            },
        )
        val warnedTypes = ConcurrentHashMap.newKeySet<String>()
        fun warnUnknownLabelTypes() {
            (typeLabels.keys - registry.current.map { it.type }.toSet()).forEach { type ->
                if (warnedTypes.add(type)) log.warn { "--type-label: no target of type '$type' (yet)" }
            }
        }

        if (includeCatalog) runBlocking { envs.forEach { env -> fetchCatalog(env)?.let { catalogs[env] = it } } }
        registry.update(buildTargetList())
        if (registry.current.isEmpty()) throw UsageError("no targets to probe: pass --endpoint/--endpoint-scope or drop --no-catalog")
        warnUnknownLabelTypes()

        val sessions = SessionRegistry { target ->
            val provider = CountingSubjectTokenProvider(tokenProvider, signingLock)
            val sdk = buildZetaSdkClient(
                target.resource, target.scopes, storagePath, provider, cliConfig,
                aslProdEnvironment = cliConfig.aslProdEnvironment || target.env == Environment.PROD,
            )
            val auth = ProfileStores(ResourceScope(target.resource, target.scopes), ProfileDb(storagePath)).authentication
            TargetSession(target, sdk, auth, provider)
        }
        val lastResult = ConcurrentHashMap<String, ProbeResult>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val scheduler = ProbeScheduler(
            targets = registry,
            runner = { kind, target -> runProbe(kind, sessions.sessionFor(target), probeTimeout, telemetry.spanScope(kind, target)) },
            onOutcome = { outcome ->
                telemetry.record(outcome)
                logOutcome(outcome, lastResult)
            },
            scope = scope,
            backoff = backoff,
        )

        liveness = { scheduler.livenessProblems(System.currentTimeMillis(), LIVENESS_GRACE, probeTimeout + LIVENESS_GRACE) }

        Runtime.getRuntime().addShutdownHook(
            Thread {
                log.info { "zeta probe shutting down" }
                health?.close()
                scheduler.close()
                sessions.close()
                runCatching { auth.close() }
                runCatching { otel.close() }
                runCatching { cliConfig.httpClient.close() }
            },
        )

        echo(
            "zeta probe ready — metrics and traces to ${effective.otlpEndpoint} every ${effective.exportIntervalMs / 1000}s" +
                (health?.let { ", health on http://$healthHost:${it.port}/healthz" } ?: ", health endpoints off") +
                " — env $envList, profile $profile, ${registry.current.size} target(s), " +
                "login every ${describe(loginInterval)}, refresh every ${describe(refreshInterval)}, " +
                "backoff up to ${describe(backoffMax)} — Ctrl-C to stop",
        )
        registry.current.forEach { log.info { "target ${it.envLabel}/${it.service} type=${it.type}: ${it.resource} scopes=${it.scopes}${labelsSuffix(it)}" } }

        scheduler.start(ProbeKind.LOGIN, loginInterval)
        scheduler.start(ProbeKind.REFRESH, refreshInterval)
        if (includeCatalog) {
            val refreshLock = Mutex()
            envs.forEach { env ->
                scheduler.startPeriodic("catalog refresh ${env.name.lowercase()}", catalogInterval) {
                    val fresh = fetchCatalog(env) ?: return@startPeriodic
                    catalogs[env] = fresh
                    refreshLock.withLock {
                        val (added, removed) = registry.update(buildTargetList())
                        removed.forEach { sessions.evict(it); telemetry.forget(it); backoff.forget(it) }
                        warnUnknownLabelTypes()
                        if (added.isNotEmpty() || removed.isNotEmpty()) {
                            log.info { "catalog refresh ${env.name.lowercase()}: +${added.map { it.service }} -${removed.map { it.service }}" }
                        }
                    }
                }
            }
        }
        started.set(true)
        CountDownLatch(1).await() // hold the foreground; the shutdown hook cleans up on SIGTERM / Ctrl-C
    }

    private fun labelsSuffix(t: ProbeTarget) = if (t.labels.isEmpty()) "" else " labels=${t.labels}"

    private fun logOutcome(o: ProbeOutcome, lastResult: ConcurrentHashMap<String, ProbeResult>) {
        val key = "${o.kind.label}|${o.target.key}"
        val previous = lastResult.put(key, o.result)
        val summary = "${o.kind.label} ${o.target.envLabel}/${o.target.service} (${o.target.resource}) ${o.result.label} in ${o.total} " +
            o.steps.joinToString(" ", prefix = "[", postfix = "]") { "${it.step}=${it.duration}" }
        when {
            o.result != ProbeResult.OK -> log.warn { "$summary: ${o.errorType}${o.errorMessage?.let { ": $it" } ?: ""}" }
            o.fallback && o.preStatus != SdkStatus.HAS_REFRESH_TOKEN -> log.info { "$summary — no refresh token yet, full exchange instead" }
            o.fallback -> log.warn { "$summary — refresh grant did not happen, the SDK fell back to a full exchange" }
            previous != null && previous != ProbeResult.OK -> log.info { "$summary (recovered)" }
            o.registered -> log.info { "$summary (registered)" }
            else -> log.debug { summary }
        }
    }

    private fun describe(d: Duration) = if (d <= Duration.ZERO) "off" else d.toString()
}
