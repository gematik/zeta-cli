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
import com.github.ajalt.clikt.parameters.types.enum
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex

private val log = KotlinLogging.logger {}

private val LIST_SPLIT = Regex("[,\\s]+")

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
 * `zeta probe` — a permanently running prober for one TI environment. Every target (the env's catalog
 * VSDM instances + PoPP service, plus explicit `--endpoint`s) gets a `login` probe (forced full token
 * exchange) and a `refresh` probe (forced refresh grant) on their own intervals, spread round-robin so
 * `n` targets on a `T` interval see one probe every `T/n`. Metrics are served for Prometheus on
 * `/metrics`; spans go out via OTLP when an endpoint is configured.
 */
class ProbeCommand : ZetaSessionCommand(name = "probe") {
    private val env: Environment by option(
        "--env",
        metavar = "ENV",
        envvar = "ZETA_ENV",
        help = "TI environment to probe: dev (default), ref, test, or prod. Selects the service-discovery " +
            "catalog, the PoPP service URL, and ASL prod/non-prod. (env: ZETA_ENV)",
    ).enum<Environment>(ignoreCase = true).default(Environment.DEV)

    private val endpointOpt: List<List<String>> by option(
        "--endpoint",
        metavar = "URL",
        envvar = "ZETA_PROBE_ENDPOINTS",
        help = "Additional Zeta-protected endpoint to probe (repeatable; the env var takes a whitespace- or " +
            "comma-separated list). Each one needs a matching --endpoint-scope. (env: ZETA_PROBE_ENDPOINTS)",
    ).split(LIST_SPLIT).multiple()

    private val endpointScopeOpt: List<List<String>> by option(
        "--endpoint-scope",
        metavar = "SCOPE",
        envvar = "ZETA_PROBE_ENDPOINT_SCOPES",
        help = "OAuth scope for the --endpoint at the same position (repeatable; same list form in the env " +
            "var). Counts must match. (env: ZETA_PROBE_ENDPOINT_SCOPES)",
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
        help = "PoPP service to probe. Defaults to the URL derived from --env. (env: ZETA_POPP_SERVICE_URL)",
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

    private val probeTimeout: Duration by durationOption(
        "--probe-timeout", "ZETA_PROBE_TIMEOUT",
        "Upper bound for one probe run; a slower probe counts as a timeout. Default: 2m.",
    ).default(2.minutes)

    private val metricsHost: String? by option(
        "--metrics-host",
        metavar = "ADDR",
        envvar = "ZETA_PROBE_METRICS_HOST",
        help = "Bind address of the Prometheus /metrics endpoint. Default: 0.0.0.0. (env: ZETA_PROBE_METRICS_HOST)",
    )

    private val metricsPort: Int? by option(
        "--metrics-port",
        metavar = "PORT",
        envvar = "ZETA_PROBE_METRICS_PORT",
        help = "Port of the Prometheus /metrics endpoint. Default: 9464. (env: ZETA_PROBE_METRICS_PORT)",
    ).int()

    private val otlpEndpoint: String? by option(
        "--otlp-endpoint",
        metavar = "URL",
        help = "OTLP endpoint to push spans to; traces are off without one. Equivalent to OTEL_EXPORTER_OTLP_ENDPOINT. " +
            "Every other OTEL_* variable (headers, certificates, mTLS, compression, resource attributes) is honoured as-is.",
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
        help = "OTLP transport: http/protobuf (default) or grpc. Equivalent to OTEL_EXPORTER_OTLP_PROTOCOL.",
    ).choice("http/protobuf", "grpc")

    override fun help(context: Context) =
        "Continuously probe the auth flows of one TI environment's endpoints; serve Prometheus metrics, push OTLP traces."

    private fun durationOption(name: String, envvar: String, help: String) =
        option(name, metavar = "DURATION", envvar = envvar, help = "$help (env: $envvar)")
            .convert { raw -> runCatching { parseProbeDuration(raw) }.getOrElse { fail(it.message ?: "invalid duration") } }

    override fun runCommand() {
        if (Tracer.enabled) {
            throw UsageError("--trace is not supported by zeta probe (its span tree would grow for the life of the process); use --otlp-endpoint")
        }
        if (probeTimeout <= Duration.ZERO) throw UsageError("--probe-timeout must be positive")
        val extras = pairEndpoints(endpointOpt.flatten().filter { it.isNotBlank() }, endpointScopeOpt.flatten().filter { it.isNotBlank() })
        if (loginInterval <= Duration.ZERO && refreshInterval <= Duration.ZERO) {
            throw UsageError("both --login-interval and --refresh-interval are off; nothing to probe")
        }

        cliConfig.aslProdEnvironment = cliConfig.aslProdEnvironment || env == Environment.PROD
        val storagePath = zetaProfilePath(profile)
        val poppUrl = poppServiceUrlOverride ?: poppServiceUrlFor(env)
        val includeCatalog = !noCatalog

        val (tokenProvider, connectorSession) = buildTokenProvider()
        val signingLock = Mutex().takeIf { connectorSession != null }

        val catalog = if (includeCatalog) runBlocking { fetchCatalog(storagePath) } else null
        val registry = TargetRegistry(resolveTargets(catalog, poppUrl, includeCatalog, extras))
        if (registry.current.isEmpty()) throw UsageError("no targets to probe: pass --endpoint/--endpoint-scope or drop --no-catalog")

        val (otel, effective) = buildOpenTelemetry(
            TelemetryOptions(env.name.lowercase(), metricsHost, metricsPort, otlpEndpoint, otlpHeaders, otlpProtocol),
            BuildConfig.VERSION,
        )
        val telemetry = ProbeTelemetry(otel, env.name.lowercase()) { registry.current.size }
        val sessions = SessionRegistry { target ->
            val provider = CountingSubjectTokenProvider(tokenProvider, signingLock)
            val sdk = buildZetaSdkClient(target.resource, target.scopes, storagePath, provider, cliConfig)
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
        )

        Runtime.getRuntime().addShutdownHook(
            Thread {
                log.info { "zeta probe shutting down" }
                scheduler.close()
                sessions.close()
                runCatching { connectorSession?.close() }
                runCatching { otel.close() }
                runCatching { cliConfig.httpClient.close() }
            },
        )

        echo(
            "zeta probe ready — metrics on http://${effective.metricsHost}:${effective.metricsPort}/metrics" +
                (effective.otlpEndpoint?.let { ", traces to $it" } ?: ", traces off") +
                " — env ${env.name.lowercase()}, profile $profile, ${registry.current.size} target(s), " +
                "login every ${describe(loginInterval)}, refresh every ${describe(refreshInterval)} — Ctrl-C to stop",
        )
        registry.current.forEach { log.info { "target ${it.service}: ${it.resource} scopes=${it.scopes}" } }

        scheduler.start(ProbeKind.LOGIN, loginInterval)
        scheduler.start(ProbeKind.REFRESH, refreshInterval)
        if (includeCatalog) {
            scheduler.startPeriodic(catalogInterval) {
                val fresh = fetchCatalog(storagePath) ?: return@startPeriodic
                val (added, removed) = registry.update(resolveTargets(fresh, poppUrl, true, extras))
                removed.forEach { sessions.evict(it); telemetry.forget(it) }
                if (added.isNotEmpty() || removed.isNotEmpty()) {
                    log.info { "catalog refresh: +${added.map { it.service }} -${removed.map { it.service }}" }
                }
            }
        }
        CountDownLatch(1).await() // hold the foreground; the shutdown hook cleans up on SIGTERM / Ctrl-C
    }

    private suspend fun fetchCatalog(storagePath: java.nio.file.Path): ServiceCatalog? =
        runCatching {
            ServiceDiscoveryClient(cliConfig.httpClient, ProfileDbCatalogStore(ProfileDb(storagePath))).fetchCatalog(env)
        }.onFailure {
            log.warn { "service-discovery catalog fetch failed for ${env.name.lowercase()}: ${it.message}" }
        }.getOrNull()

    private fun logOutcome(o: ProbeOutcome, lastResult: ConcurrentHashMap<String, ProbeResult>) {
        val key = "${o.kind.label}|${o.target.key}"
        val previous = lastResult.put(key, o.result)
        val summary = "${o.kind.label} ${o.target.service} (${o.target.resource}) ${o.result.label} in ${o.total} " +
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
