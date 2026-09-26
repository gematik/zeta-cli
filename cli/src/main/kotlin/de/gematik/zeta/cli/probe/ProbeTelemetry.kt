package de.gematik.zeta.cli.probe

import com.github.ajalt.clikt.core.UsageError
import de.gematik.zeta.catalog.Environment
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.DurationUnit

internal const val OTLP_PROTOCOL = "http/protobuf"
private const val DEFAULT_EXPORT_INTERVAL_MS = 30_000L
private val SIGNALS = listOf("metrics", "traces")

internal data class TelemetryOptions(
    val envs: List<String>,
    val otlpEndpoint: String? = null,
    val otlpHeaders: List<String> = emptyList(),
    val otlpProtocol: String? = null,
    /** `service.instance.id` unless `OTEL_RESOURCE_ATTRIBUTES` sets one; the container's host name in Docker/Kubernetes. */
    val instanceId: String = defaultInstanceId(),
)

internal fun defaultInstanceId(): String =
    System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }
        ?: runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown")

/** What the SDK ended up configured with, for the ready line. */
internal data class EffectiveTelemetry(val otlpEndpoint: String, val exportIntervalMs: Long)

/**
 * Reads an autoconfigure property the way the SDK does before any supplier applies: the `-Dotel.*`
 * system property, else the `OTEL_*` environment variable.
 */
internal fun otelEnvLookup(key: String): String? =
    System.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(key.uppercase().replace('.', '_').replace('-', '_'))?.takeIf { it.isNotBlank() }

/**
 * Refuses configurations `zeta probe` cannot honour, before autoconfigure runs and fails with a less
 * helpful message: exporters other than `otlp`/`none`, a protocol other than `http/protobuf` (the JDK
 * sender has no gRPC), and a signal that is exported without an endpoint. [lookup] reads the user's
 * configuration (system properties, then environment).
 */
internal fun validateOtelConfig(opts: TelemetryOptions, lookup: (String) -> String?) {
    val exporting = SIGNALS.filter { signal ->
        val exporter = lookup("otel.$signal.exporter")?.trim()
        if (exporter != null && exporter != "otlp" && exporter != "none") {
            throw UsageError("unsupported $signal exporter '$exporter': zeta probe pushes via OTLP (allowed: otlp, none)")
        }
        exporter != "none"
    }
    (listOf("otel.exporter.otlp.protocol") + SIGNALS.map { "otel.exporter.otlp.$it.protocol" }).forEach { key ->
        val protocol = lookup(key)?.trim() ?: return@forEach
        if (protocol != OTLP_PROTOCOL) {
            throw UsageError("unsupported OTLP protocol '$protocol' ($key): zeta probe supports only $OTLP_PROTOCOL")
        }
    }
    if (opts.otlpEndpoint != null || lookup("otel.exporter.otlp.endpoint") != null) return
    val missing = exporting.filter { lookup("otel.exporter.otlp.$it.endpoint") == null }
    if (missing.isNotEmpty()) throw UsageError("no OTLP endpoint: set OTEL_EXPORTER_OTLP_ENDPOINT or --otlp-endpoint")
}

/** Lowest-precedence properties: every `OTEL_*` env var and `-Dotel.*` system property beats these. */
internal fun otelDefaultProperties(): Map<String, String> = mapOf(
    "otel.service.name" to "zeta-probe",
    "otel.metrics.exporter" to "otlp",
    "otel.traces.exporter" to "otlp",
    "otel.logs.exporter" to "none",
    "otel.metric.export.interval" to DEFAULT_EXPORT_INTERVAL_MS.toString(),
    "otel.exporter.otlp.protocol" to OTLP_PROTOCOL,
    "otel.exporter.otlp.metrics.temporality.preference" to "cumulative",
)

/**
 * Highest-precedence properties: the explicit CLI flags, and the resource attributes merged into what
 * the user set, so `OTEL_RESOURCE_ATTRIBUTES` can add or override keys without dropping ours. [lookup]
 * reads the merged configuration and returns null for keys nobody set.
 */
internal fun otelOverrideProperties(opts: TelemetryOptions, version: String, lookup: (String) -> String?): Map<String, String> {
    val out = mutableMapOf<String, String>()
    opts.otlpEndpoint?.let { out["otel.exporter.otlp.endpoint"] = it }
    opts.otlpProtocol?.let { out["otel.exporter.otlp.protocol"] = it }
    mergeOtlpHeaders(lookup("otel.exporter.otlp.headers"), opts.otlpHeaders)?.let { out["otel.exporter.otlp.headers"] = it }
    // The OTel parser splits the value on commas, so the environment only fits when there is exactly one;
    // every series carries `env` anyway.
    val defaults = buildMap {
        put("service.version", version)
        opts.envs.singleOrNull()?.let { put("deployment.environment.name", it) }
        put("service.instance.id", opts.instanceId)
    }
    out["otel.resource.attributes"] = mergeResourceAttributes(lookup("otel.resource.attributes"), defaults)
    return out
}

/** `k=v,k2=v2` from the user with every key of [defaults] they did not set appended. */
internal fun mergeResourceAttributes(existing: String?, defaults: Map<String, String>): String {
    val given = existing?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    val keys = given.map { it.substringBefore('=').trim() }.toSet()
    return (given + defaults.filterKeys { it !in keys }.map { (k, v) -> "$k=$v" }).joinToString(",")
}

/** `KEY=VALUE` flags appended to an existing `k=v,k2=v2` list (the OTel env-var format). */
internal fun mergeOtlpHeaders(existing: String?, flags: List<String>): String? {
    flags.forEach { raw ->
        val eq = raw.indexOf('=')
        if (eq <= 0) throw UsageError("--otlp-header expects KEY=VALUE, got '$raw'")
    }
    val parts = buildList {
        existing?.takeIf { it.isNotBlank() }?.let { add(it) }
        addAll(flags)
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(",")
}

internal fun buildOpenTelemetry(opts: TelemetryOptions, version: String): Pair<OpenTelemetrySdk, EffectiveTelemetry> {
    validateOtelConfig(opts, ::otelEnvLookup)
    bridgeOtelLogging()
    var effective = EffectiveTelemetry("", DEFAULT_EXPORT_INTERVAL_MS)
    val sdk = AutoConfiguredOpenTelemetrySdk.builder()
        .disableShutdownHook()
        .addPropertiesSupplier { otelDefaultProperties() }
        .addPropertiesCustomizer { cfg ->
            val overrides = otelOverrideProperties(opts, version) { cfg.getString(it) }
            val merged = { key: String -> overrides[key] ?: cfg.getString(key) }
            effective = EffectiveTelemetry(
                otlpEndpoint = merged("otel.exporter.otlp.endpoint")
                    ?: SIGNALS.mapNotNull { merged("otel.exporter.otlp.$it.endpoint") }.distinct().joinToString(" + "),
                exportIntervalMs = merged("otel.metric.export.interval")?.toLongOrNull() ?: DEFAULT_EXPORT_INTERVAL_MS,
            )
            overrides
        }
        .build()
        .openTelemetrySdk
    return sdk to effective
}

private val otelJulLogger: Logger = Logger.getLogger("io.opentelemetry")

/**
 * The SDK logs through java.util.logging, which would bypass Logback's format and levels; route its
 * `io.opentelemetry` tree into the CLI's logging. A failed export is reported at SEVERE with a stack
 * trace; for a prober that only means delayed telemetry, so it becomes a one-line WARN and the trace
 * moves to DEBUG. The SDK's own throttling (one line per failure burst) is kept as is.
 */
internal fun bridgeOtelLogging() {
    if (otelJulLogger.handlers.any { it is Slf4jBridgeHandler }) return
    otelJulLogger.useParentHandlers = false
    otelJulLogger.level = Level.INFO
    otelJulLogger.addHandler(Slf4jBridgeHandler())
}

private class Slf4jBridgeHandler : Handler() {
    override fun publish(record: LogRecord) {
        val log = KotlinLogging.logger(record.loggerName ?: "io.opentelemetry")
        val message = record.message ?: ""
        val cause = record.thrown
        val level = record.level.intValue()
        when {
            level >= Level.WARNING.intValue() -> {
                log.warn { cause?.let { "$message: ${it.message ?: it::class.simpleName}" } ?: message }
                if (cause != null) log.debug(cause) { message }
            }
            level >= Level.INFO.intValue() -> log.info(cause) { message }
            else -> log.debug(cause) { message }
        }
    }

    override fun flush() = Unit
    override fun close() = Unit
}

private val ENV = AttributeKey.stringKey("env")
private val SERVICE = AttributeKey.stringKey("service")
private val TYPE = AttributeKey.stringKey("type")
private val ENDPOINT = AttributeKey.stringKey("endpoint")
private val PROBE = AttributeKey.stringKey("probe")
private val STEP = AttributeKey.stringKey("step")
private val RESULT = AttributeKey.stringKey("result")
private val ERROR_TYPE = AttributeKey.stringKey("error_type")
private val FALLBACK = AttributeKey.stringKey("fallback")

/**
 * The probe's instruments and spans on one [OpenTelemetry] instance. Gauges are observed from
 * in-memory maps keyed by their fully built [Attributes] (value equality), updated by [record], so a
 * scrape allocates nothing and never touches the profile database.
 */
internal class ProbeTelemetry(
    otel: OpenTelemetry,
    private val targets: () -> List<ProbeTarget>,
    private val backoff: () -> List<BackoffEntry> = { emptyList() },
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val meter = otel.getMeter(SCOPE)
    private val tracer: Tracer = otel.getTracer(SCOPE)

    private val duration = meter.histogramBuilder("zeta.probe.duration")
        .setDescription("Wall time of one probe step (step=total for the whole probe)")
        .setUnit("s")
        .setExplicitBucketBoundariesAdvice(listOf(0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 30.0, 60.0, 120.0))
        .build()
    private val runs = meter.counterBuilder("zeta.probe.runs")
        .setDescription("Completed probes by outcome")
        .setUnit("{run}")
        .build()

    private val targetAttrs = ConcurrentHashMap<String, Attributes>()
    private val up = ConcurrentHashMap<Attributes, Long>()
    private val lastSuccess = ConcurrentHashMap<Attributes, Long>()
    private val lastRun = ConcurrentHashMap<Attributes, Long>()
    private val tokenExpiry = ConcurrentHashMap<Attributes, Long>()
    private val catalogFetchedAt = ConcurrentHashMap<String, Long>()

    init {
        meter.gaugeBuilder("zeta.probe.up").ofLongs()
            .setDescription("1 if the last probe of this kind against the endpoint succeeded, else 0")
            .buildWithCallback { m -> up.forEach { (k, v) -> m.record(v, k) } }
        meter.gaugeBuilder("zeta.probe.last_success.time").ofLongs().setUnit("s")
            .setDescription("Unix time of the last successful probe of this kind against the endpoint")
            .buildWithCallback { m -> lastSuccess.forEach { (k, v) -> m.record(v, k) } }
        meter.gaugeBuilder("zeta.probe.last_run.time").ofLongs().setUnit("s")
            .setDescription("Unix time of the last probe of this kind against the endpoint, whatever its result")
            .buildWithCallback { m -> lastRun.forEach { (k, v) -> m.record(v, k) } }
        meter.gaugeBuilder("zeta.probe.token.expiry.time").ofLongs().setUnit("s")
            .setDescription("Unix time at which the endpoint's current access token expires")
            .buildWithCallback { m -> tokenExpiry.forEach { (k, v) -> m.record(v, k) } }
        meter.gaugeBuilder("zeta.probe.backoff.until.time").ofLongs().setUnit("s")
            .setDescription("Unix time until which a failing target is held off; absent when it is not")
            .buildWithCallback { m -> backoff().forEach { e -> m.record(e.dueAtEpochMs / 1000, probeAttrs(e.target, e.kind.label)) } }
        meter.gaugeBuilder("zeta.probe.catalog.age").ofLongs().setUnit("s")
            .setDescription("Age of the service-discovery catalog copy the target list is built from")
            .buildWithCallback { m -> val now = clock(); catalogFetchedAt.forEach { (env, at) -> m.record(now - at, Attributes.of(ENV, env)) } }
        meter.gaugeBuilder("zeta.probe.targets").ofLongs()
            .setDescription("Number of endpoints currently being probed")
            .buildWithCallback { m ->
                targets().groupingBy { it.envLabel }.eachCount().forEach { (env, n) -> m.record(n.toLong(), Attributes.of(ENV, env)) }
            }
    }

    /** The per-target base attributes (env, service, type, endpoint, caller labels), built once. */
    private fun attrsOf(target: ProbeTarget): Attributes = targetAttrs.computeIfAbsent(target.key) {
        Attributes.builder()
            .put(ENV, target.envLabel).put(SERVICE, target.service).put(TYPE, target.type).put(ENDPOINT, target.resource)
            .apply { target.labels.forEach { (k, v) -> put(AttributeKey.stringKey(k), v) } }
            .build()
    }

    private fun probeAttrs(target: ProbeTarget, probe: String): Attributes =
        Attributes.builder().putAll(attrsOf(target)).put(PROBE, probe).build()

    fun spanScope(kind: ProbeKind, target: ProbeTarget): ProbeSpanScope = OtelSpanScope(tracer, kind, target)

    fun record(outcome: ProbeOutcome) {
        val base = attrsOf(outcome.target)
        val probe = outcome.kind.label
        val result = outcome.result.label
        fun stepAttrs(step: String, stepResult: String): Attributes =
            Attributes.builder().putAll(base).put(PROBE, probe).put(STEP, step).put(RESULT, stepResult).build()
        outcome.steps.forEach { s ->
            duration.record(s.duration.toDouble(DurationUnit.SECONDS), stepAttrs(s.step, if (s.ok) "ok" else "error"))
        }
        duration.record(outcome.total.toDouble(DurationUnit.SECONDS), stepAttrs(STEP_TOTAL, result))
        runs.add(
            1,
            Attributes.builder().putAll(base).put(PROBE, probe).put(RESULT, result)
                .put(ERROR_TYPE, outcome.errorType ?: "").put(FALLBACK, outcome.fallback.toString())
                .build(),
        )
        val series = probeAttrs(outcome.target, probe)
        val now = clock()
        val ok = outcome.result == ProbeResult.OK
        up[series] = if (ok) 1 else 0
        lastRun[series] = now
        if (ok) lastSuccess[series] = now
        outcome.tokenExpiryEpochSec?.let { tokenExpiry[base] = it }
    }

    fun recordCatalogFetchedAt(env: Environment, fetchedAtEpochSec: Long) {
        catalogFetchedAt[env.name.lowercase()] = fetchedAtEpochSec
    }

    /** Drop the gauge series of an evicted target so they stop being exported. */
    fun forget(target: ProbeTarget) {
        val gone = { attrs: Attributes -> attrs.get(ENDPOINT) == target.resource && attrs.get(ENV) == target.envLabel }
        up.keys.removeIf(gone)
        lastSuccess.keys.removeIf(gone)
        lastRun.keys.removeIf(gone)
        tokenExpiry.keys.removeIf(gone)
        targetAttrs.remove(target.key)
    }

    private companion object {
        const val SCOPE = "de.gematik.zeta.cli.probe"
    }
}

/**
 * One root span per probe with a child per step. Parents are passed explicitly: the SDK hops
 * dispatchers internally, so the thread-bound OTel context would not survive into `createSubjectToken`.
 * A scope is used by exactly one probe at a time (the target mutex), so the plain `current` var is safe.
 */
private class OtelSpanScope(
    private val tracer: Tracer,
    kind: ProbeKind,
    target: ProbeTarget,
) : ProbeSpanScope {
    private val root: Span = tracer.spanBuilder("zeta.probe.${kind.label}")
        .setNoParent()
        .setAttribute("zeta.env", target.envLabel)
        .setAttribute("zeta.service", target.service)
        .setAttribute("zeta.type", target.type)
        .setAttribute("zeta.endpoint", target.resource)
        .setAttribute("zeta.scopes", target.scopes.joinToString(","))
        .setAttribute("zeta.probe", kind.label)
        .apply { target.labels.forEach { (k, v) -> setAttribute(k, v) } }
        .startSpan()

    @Volatile
    private var current: Span = root

    override suspend fun <T> step(name: String, block: suspend () -> T): T {
        val parent = current
        val span = tracer.spanBuilder(spanName(name)).setParent(Context.root().with(parent)).startSpan()
        current = span
        try {
            return block()
        } catch (e: Throwable) {
            span.setStatus(StatusCode.ERROR, e.message ?: e::class.simpleName ?: "")
            if (e !is CancellationException) span.recordException(e)
            throw e
        } finally {
            span.end()
            current = parent
        }
    }

    override fun end(outcome: ProbeOutcome) {
        root.setAttribute("zeta.result", outcome.result.label)
        root.setAttribute("zeta.fallback", outcome.fallback)
        root.setAttribute("zeta.registered", outcome.registered)
        outcome.preStatus?.let { root.setAttribute("zeta.status.before", it.name) }
        outcome.postStatus?.let { root.setAttribute("zeta.status.after", it.name) }
        if (outcome.result != ProbeResult.OK) {
            root.setStatus(StatusCode.ERROR, listOfNotNull(outcome.errorType, outcome.errorMessage).joinToString(": "))
        }
        root.end()
    }

    private fun spanName(step: String) = when (step) {
        STEP_AUTHENTICATE, STEP_SUBJECT_TOKEN -> "sdk.$step"
        else -> "probe.$step"
    }
}
