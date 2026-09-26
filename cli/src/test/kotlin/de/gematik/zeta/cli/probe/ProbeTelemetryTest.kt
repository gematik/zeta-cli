package de.gematik.zeta.cli.probe

import de.gematik.zeta.catalog.Environment
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProbeTelemetryTest {
    private val reader = InMemoryMetricReader.create()
    private val spans = InMemorySpanExporter.create()
    private val otel: OpenTelemetrySdk = OpenTelemetrySdk.builder()
        .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
        .build()
    private val dev = ProbeTarget(Environment.DEV, "https://a/", listOf("s"), "a", "vsdm", mapOf("criticality" to "high"))
    private val ref = ProbeTarget(Environment.REF, "https://r/", listOf("s"), "r", "vsdm")
    private var now = 1_000_000L
    private var backoffEntries = emptyList<BackoffEntry>()
    private val telemetry = ProbeTelemetry(otel, { listOf(dev, ref, ref.copy(resource = "https://r2/", service = "r2")) }, { backoffEntries }, { now })

    private fun metric(name: String): MetricData = reader.collectAllMetrics().first { it.name == name }

    @Test
    fun `a successful login is recorded as metrics and a span tree`() = runBlocking {
        val signer = FakeSigner()
        val counting = CountingSubjectTokenProvider(signer)
        val target = dev
        val sdk = FakeSdk(FakeAuth(), counting)
        val outcome = runProbe(ProbeKind.LOGIN, fakeSession(sdk, counting, target), 10.seconds, telemetry.spanScope(ProbeKind.LOGIN, target))
        telemetry.record(outcome)

        val runs = metric("zeta.probe.runs").longSumData.points.single()
        assertEquals(1L, runs.value)
        assertEquals("login", runs.attributes.get(AttributeKey.stringKey("probe")))
        assertEquals("ok", runs.attributes.get(AttributeKey.stringKey("result")))
        assertEquals("false", runs.attributes.get(AttributeKey.stringKey("fallback")))
        assertEquals("https://a/", runs.attributes.get(AttributeKey.stringKey("endpoint")))
        assertEquals("a", runs.attributes.get(AttributeKey.stringKey("service")))
        assertEquals("vsdm", runs.attributes.get(AttributeKey.stringKey("type")))
        assertEquals("high", runs.attributes.get(AttributeKey.stringKey("criticality")))
        assertEquals("dev", runs.attributes.get(AttributeKey.stringKey("env")))

        val steps = metric("zeta.probe.duration").histogramData.points.map { it.attributes.get(AttributeKey.stringKey("step")) }.toSet()
        assertEquals(setOf(STEP_STATUS, STEP_CLEAR, STEP_AUTHENTICATE, STEP_SUBJECT_TOKEN, STEP_VERIFY, STEP_TOTAL), steps)
        assertEquals("s", metric("zeta.probe.duration").unit)

        val upPoint = metric("zeta.probe.up").longGaugeData.points.single()
        assertEquals(1L, upPoint.value)
        assertEquals("a", upPoint.attributes.get(AttributeKey.stringKey("service")))
        assertEquals("high", upPoint.attributes.get(AttributeKey.stringKey("criticality")))
        assertEquals(now, metric("zeta.probe.last_success.time").longGaugeData.points.single().value)
        assertEquals(now, metric("zeta.probe.last_run.time").longGaugeData.points.single().value)
        val expiry = metric("zeta.probe.token.expiry.time").longGaugeData.points.single()
        assertEquals(sdk.auth.exp, expiry.value)
        assertEquals("high", expiry.attributes.get(AttributeKey.stringKey("criticality")))
        val targets = metric("zeta.probe.targets").longGaugeData.points.associate { it.attributes.get(AttributeKey.stringKey("env")) to it.value }
        assertEquals(mapOf("dev" to 1L, "ref" to 2L), targets)

        val finished = spans.finishedSpanItems
        val root = finished.single { it.name == "zeta.probe.login" }
        assertEquals("ok", root.attributes.get(AttributeKey.stringKey("zeta.result")))
        assertEquals("a", root.attributes.get(AttributeKey.stringKey("zeta.service")))
        assertEquals("vsdm", root.attributes.get(AttributeKey.stringKey("zeta.type")))
        assertEquals("dev", root.attributes.get(AttributeKey.stringKey("zeta.env")))
        assertEquals("high", root.attributes.get(AttributeKey.stringKey("criticality")))
        val byName = finished.associateBy { it.name }
        assertEquals(root.spanId, byName.getValue("probe.status").parentSpanId)
        assertEquals(root.spanId, byName.getValue("sdk.authenticate").parentSpanId)
        assertEquals(byName.getValue("sdk.authenticate").spanId, byName.getValue("sdk.subject_token").parentSpanId)
        assertTrue(finished.all { it.traceId == root.traceId })
    }

    @Test
    fun `a failed probe flips the up gauge and marks the span`() = runBlocking {
        val signer = FakeSigner()
        val counting = CountingSubjectTokenProvider(signer)
        val target = dev
        val sdk = FakeSdk(FakeAuth(), counting, exchangeFails = true)
        val outcome = runProbe(ProbeKind.LOGIN, fakeSession(sdk, counting, target), 10.seconds, telemetry.spanScope(ProbeKind.LOGIN, target))
        telemetry.record(outcome)

        assertEquals(0L, metric("zeta.probe.up").longGaugeData.points.single().value)
        assertEquals(now, metric("zeta.probe.last_run.time").longGaugeData.points.single().value)
        assertTrue(reader.collectAllMetrics().none { it.name == "zeta.probe.last_success.time" && it.longGaugeData.points.isNotEmpty() })
        val runs = metric("zeta.probe.runs").longSumData.points.single()
        assertEquals("error", runs.attributes.get(AttributeKey.stringKey("result")))
        assertEquals("IllegalStateException", runs.attributes.get(AttributeKey.stringKey("error_type")))
        val root = spans.finishedSpanItems.single { it.name == "zeta.probe.login" }
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, root.status.statusCode)

        telemetry.forget(target)
        assertTrue(reader.collectAllMetrics().none { it.name == "zeta.probe.up" && it.longGaugeData.points.isNotEmpty() })
    }

    @Test
    fun `catalog age and backoff gauges come from the injected state`() {
        assertTrue(reader.collectAllMetrics().none { it.name == "zeta.probe.catalog.age" && it.longGaugeData.points.isNotEmpty() })
        telemetry.recordCatalogFetchedAt(Environment.DEV, now - 300)
        val age = metric("zeta.probe.catalog.age").longGaugeData.points.single()
        assertEquals(300L, age.value)
        assertEquals("dev", age.attributes.get(AttributeKey.stringKey("env")))
        now += 60
        assertEquals(360L, metric("zeta.probe.catalog.age").longGaugeData.points.single().value)

        backoffEntries = listOf(BackoffEntry(ProbeKind.REFRESH, dev, 2, 5_000_000L))
        val until = metric("zeta.probe.backoff.until.time").longGaugeData.points.single()
        assertEquals(5_000L, until.value)
        assertEquals("refresh", until.attributes.get(AttributeKey.stringKey("probe")))
        assertEquals("high", until.attributes.get(AttributeKey.stringKey("criticality")))
        backoffEntries = emptyList()
        assertTrue(reader.collectAllMetrics().none { it.name == "zeta.probe.backoff.until.time" && it.longGaugeData.points.isNotEmpty() })
    }

    @Test
    fun `forget drops only the evicted target's series`() = runBlocking {
        val counting = CountingSubjectTokenProvider(FakeSigner())
        telemetry.record(runProbe(ProbeKind.LOGIN, fakeSession(FakeSdk(FakeAuth(), counting), counting, dev), 10.seconds))
        telemetry.record(runProbe(ProbeKind.LOGIN, fakeSession(FakeSdk(FakeAuth(), counting), counting, ref), 10.seconds))
        assertEquals(2, metric("zeta.probe.up").longGaugeData.points.size)
        telemetry.forget(dev)
        val left = metric("zeta.probe.up").longGaugeData.points.single()
        assertEquals("ref", left.attributes.get(AttributeKey.stringKey("env")))
        assertEquals(1, metric("zeta.probe.last_run.time").longGaugeData.points.size)
    }

    @Test
    fun `instrument names, units and attribute keys are the documented contract`() = runBlocking {
        val counting = CountingSubjectTokenProvider(FakeSigner())
        telemetry.record(runProbe(ProbeKind.LOGIN, fakeSession(FakeSdk(FakeAuth(), counting), counting, dev), 10.seconds))
        telemetry.recordCatalogFetchedAt(Environment.DEV, now - 10)
        backoffEntries = listOf(BackoffEntry(ProbeKind.REFRESH, dev, 1, 5_000_000L))

        val target = setOf("env", "service", "type", "endpoint", "criticality")
        val expected = mapOf(
            "zeta.probe.duration" to ("s" to target + setOf("probe", "step", "result")),
            "zeta.probe.runs" to ("{run}" to target + setOf("probe", "result", "error_type", "fallback")),
            "zeta.probe.up" to ("" to target + "probe"),
            "zeta.probe.last_success.time" to ("s" to target + "probe"),
            "zeta.probe.last_run.time" to ("s" to target + "probe"),
            "zeta.probe.token.expiry.time" to ("s" to target),
            "zeta.probe.backoff.until.time" to ("s" to target + "probe"),
            "zeta.probe.catalog.age" to ("s" to setOf("env")),
            "zeta.probe.targets" to ("" to setOf("env")),
        )
        val metrics = reader.collectAllMetrics().associateBy { it.name }
        assertEquals(expected.keys, metrics.keys)
        expected.forEach { (name, spec) ->
            val (unit, keys) = spec
            val m = metrics.getValue(name)
            assertEquals(unit, m.unit, name)
            m.data.points.forEach { p -> assertEquals(keys, p.attributes.asMap().keys.map { it.key }.toSet(), name) }
        }
    }

    @Test
    fun `closing the sdk exports the last metric batch`() {
        val exported = java.util.concurrent.CopyOnWriteArrayList<String>()
        val exporter = object : io.opentelemetry.sdk.metrics.export.MetricExporter {
            override fun export(metrics: Collection<MetricData>): io.opentelemetry.sdk.common.CompletableResultCode {
                metrics.forEach { exported += it.name }
                return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess()
            }
            override fun flush() = io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess()
            override fun shutdown() = io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess()
            override fun getAggregationTemporality(type: io.opentelemetry.sdk.metrics.InstrumentType) =
                io.opentelemetry.sdk.metrics.data.AggregationTemporality.CUMULATIVE
        }
        val sdk = OpenTelemetrySdk.builder()
            .setMeterProvider(
                SdkMeterProvider.builder()
                    .registerMetricReader(
                        io.opentelemetry.sdk.metrics.export.PeriodicMetricReader.builder(exporter)
                            .setInterval(java.time.Duration.ofHours(1)).build(),
                    ).build(),
            ).build()
        sdk.getMeter("t").counterBuilder("zeta.probe.runs").build().add(1)
        assertTrue(exported.isEmpty())
        sdk.close()
        assertEquals(listOf("zeta.probe.runs"), exported.toList())
    }
}
