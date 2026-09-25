package de.gematik.zeta.cli.probe

import com.github.ajalt.clikt.core.UsageError
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.DurationUnit

internal const val DEFAULT_METRICS_HOST = "0.0.0.0"
internal const val DEFAULT_METRICS_PORT = 9464

internal data class TelemetryOptions(
    val env: String,
    val metricsHost: String? = null,
    val metricsPort: Int? = null,
    val otlpEndpoint: String? = null,
    val otlpHeaders: List<String> = emptyList(),
    val otlpProtocol: String? = null,
)

/** What the SDK ended up configured with, for the ready line. */
internal data class EffectiveTelemetry(val metricsHost: String, val metricsPort: Int, val otlpEndpoint: String?)

/**
 * Lowest-precedence properties: every `OTEL_*` env var and `-Dotel.*` system property beats these.
 * Traces stay off unless an OTLP endpoint is configured (see [otelOverrideProperties]).
 */
internal fun otelDefaultProperties(opts: TelemetryOptions, version: String): Map<String, String> = mapOf(
    "otel.service.name" to "zeta-probe",
    "otel.resource.attributes" to "service.version=$version,deployment.environment.name=${opts.env}",
    "otel.metrics.exporter" to "prometheus",
    "otel.exporter.prometheus.host" to DEFAULT_METRICS_HOST,
    "otel.exporter.prometheus.port" to DEFAULT_METRICS_PORT.toString(),
    "otel.exporter.otlp.protocol" to "http/protobuf",
    "otel.logs.exporter" to "none",
)

/**
 * Highest-precedence properties: the explicit CLI flags, plus the traces-exporter decision. [lookup]
 * reads the merged configuration (system props, env, defaults) and returns null for keys nobody set.
 */
internal fun otelOverrideProperties(opts: TelemetryOptions, lookup: (String) -> String?): Map<String, String> {
    val out = mutableMapOf<String, String>()
    opts.metricsHost?.let { out["otel.exporter.prometheus.host"] = it }
    opts.metricsPort?.let { out["otel.exporter.prometheus.port"] = it.toString() }
    opts.otlpEndpoint?.let { out["otel.exporter.otlp.endpoint"] = it }
    opts.otlpProtocol?.let { out["otel.exporter.otlp.protocol"] = it }
    mergeOtlpHeaders(lookup("otel.exporter.otlp.headers"), opts.otlpHeaders)?.let { out["otel.exporter.otlp.headers"] = it }
    if (lookup("otel.traces.exporter") == null) {
        val endpointConfigured = opts.otlpEndpoint != null ||
            lookup("otel.exporter.otlp.endpoint") != null ||
            lookup("otel.exporter.otlp.traces.endpoint") != null
        out["otel.traces.exporter"] = if (endpointConfigured) "otlp" else "none"
    }
    return out
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
    var effective = EffectiveTelemetry(DEFAULT_METRICS_HOST, DEFAULT_METRICS_PORT, null)
    val sdk = AutoConfiguredOpenTelemetrySdk.builder()
        .disableShutdownHook()
        .addPropertiesSupplier { otelDefaultProperties(opts, version) }
        .addPropertiesCustomizer { cfg ->
            val overrides = otelOverrideProperties(opts) { cfg.getString(it) }
            val merged = { key: String -> overrides[key] ?: cfg.getString(key) }
            effective = EffectiveTelemetry(
                metricsHost = merged("otel.exporter.prometheus.host") ?: DEFAULT_METRICS_HOST,
                metricsPort = merged("otel.exporter.prometheus.port")?.toIntOrNull() ?: DEFAULT_METRICS_PORT,
                otlpEndpoint = if (merged("otel.traces.exporter") == "otlp") {
                    merged("otel.exporter.otlp.traces.endpoint") ?: merged("otel.exporter.otlp.endpoint") ?: "(SDK default)"
                } else {
                    null
                },
            )
            overrides
        }
        .build()
        .openTelemetrySdk
    return sdk to effective
}

private val ENV = AttributeKey.stringKey("env")
private val ENDPOINT = AttributeKey.stringKey("endpoint")
private val PROBE = AttributeKey.stringKey("probe")
private val STEP = AttributeKey.stringKey("step")
private val RESULT = AttributeKey.stringKey("result")
private val ERROR_TYPE = AttributeKey.stringKey("error_type")
private val FALLBACK = AttributeKey.stringKey("fallback")

private data class GaugeKey(val endpoint: String, val probe: String)

/**
 * The probe's instruments and spans on one [OpenTelemetry] instance. Gauges are observed from
 * in-memory maps updated by [record], so a scrape never touches the profile database.
 */
internal class ProbeTelemetry(otel: OpenTelemetry, private val env: String, targetCount: () -> Int) {
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

    private val up = ConcurrentHashMap<GaugeKey, Long>()
    private val lastSuccess = ConcurrentHashMap<GaugeKey, Long>()
    private val tokenExpiry = ConcurrentHashMap<String, Long>()

    init {
        meter.gaugeBuilder("zeta.probe.up").ofLongs()
            .setDescription("1 if the last probe of this kind against the endpoint succeeded, else 0")
            .buildWithCallback { m -> up.forEach { (k, v) -> m.record(v, k.attrs()) } }
        meter.gaugeBuilder("zeta.probe.last_success.time").ofLongs().setUnit("s")
            .setDescription("Unix time of the last successful probe of this kind against the endpoint")
            .buildWithCallback { m -> lastSuccess.forEach { (k, v) -> m.record(v, k.attrs()) } }
        meter.gaugeBuilder("zeta.probe.token.expiry.time").ofLongs().setUnit("s")
            .setDescription("Unix time at which the endpoint's current access token expires")
            .buildWithCallback { m -> tokenExpiry.forEach { (ep, v) -> m.record(v, Attributes.of(ENV, env, ENDPOINT, ep)) } }
        meter.gaugeBuilder("zeta.probe.targets").ofLongs()
            .setDescription("Number of endpoints currently being probed")
            .buildWithCallback { m -> m.record(targetCount().toLong(), Attributes.of(ENV, env)) }
    }

    private fun GaugeKey.attrs(): Attributes = Attributes.of(ENV, env, ENDPOINT, endpoint, PROBE, probe)

    fun spanScope(kind: ProbeKind, target: ProbeTarget): ProbeSpanScope = OtelSpanScope(tracer, kind, target, env)

    fun record(outcome: ProbeOutcome) {
        val endpoint = outcome.target.resource
        val probe = outcome.kind.label
        val result = outcome.result.label
        outcome.steps.forEach { s ->
            duration.record(
                s.duration.toDouble(DurationUnit.SECONDS),
                Attributes.of(ENV, env, ENDPOINT, endpoint, PROBE, probe, STEP, s.step, RESULT, if (s.ok) "ok" else "error"),
            )
        }
        duration.record(
            outcome.total.toDouble(DurationUnit.SECONDS),
            Attributes.of(ENV, env, ENDPOINT, endpoint, PROBE, probe, STEP, STEP_TOTAL, RESULT, result),
        )
        runs.add(
            1,
            Attributes.builder()
                .put(ENV, env).put(ENDPOINT, endpoint).put(PROBE, probe).put(RESULT, result)
                .put(ERROR_TYPE, outcome.errorType ?: "").put(FALLBACK, outcome.fallback.toString())
                .build(),
        )
        val key = GaugeKey(endpoint, probe)
        val ok = outcome.result == ProbeResult.OK
        up[key] = if (ok) 1 else 0
        if (ok) lastSuccess[key] = System.currentTimeMillis() / 1000
        outcome.tokenExpiryEpochSec?.let { tokenExpiry[endpoint] = it }
    }

    /** Drop the gauge series of an evicted target so they stop being exported. */
    fun forget(target: ProbeTarget) {
        up.keys.removeIf { it.endpoint == target.resource }
        lastSuccess.keys.removeIf { it.endpoint == target.resource }
        tokenExpiry.remove(target.resource)
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
    env: String,
) : ProbeSpanScope {
    private val root: Span = tracer.spanBuilder("zeta.probe.${kind.label}")
        .setNoParent()
        .setAttribute("zeta.env", env)
        .setAttribute("zeta.endpoint", target.resource)
        .setAttribute("zeta.scopes", target.scopes.joinToString(","))
        .setAttribute("zeta.probe", kind.label)
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
