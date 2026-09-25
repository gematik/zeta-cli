package de.gematik.zeta.cli.probe

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
    private val telemetry = ProbeTelemetry(otel, "dev") { 2 }

    private fun metric(name: String): MetricData = reader.collectAllMetrics().first { it.name == name }

    @Test
    fun `a successful login is recorded as metrics and a span tree`() = runBlocking {
        val signer = FakeSigner()
        val counting = CountingSubjectTokenProvider(signer)
        val target = ProbeTarget("https://a/", listOf("s"))
        val sdk = FakeSdk(FakeAuth(), counting)
        val outcome = runProbe(ProbeKind.LOGIN, fakeSession(sdk, counting, target), 10.seconds, telemetry.spanScope(ProbeKind.LOGIN, target))
        telemetry.record(outcome)

        val runs = metric("zeta.probe.runs").longSumData.points.single()
        assertEquals(1L, runs.value)
        assertEquals("login", runs.attributes.get(AttributeKey.stringKey("probe")))
        assertEquals("ok", runs.attributes.get(AttributeKey.stringKey("result")))
        assertEquals("false", runs.attributes.get(AttributeKey.stringKey("fallback")))
        assertEquals("https://a/", runs.attributes.get(AttributeKey.stringKey("endpoint")))
        assertEquals("dev", runs.attributes.get(AttributeKey.stringKey("env")))

        val steps = metric("zeta.probe.duration").histogramData.points.map { it.attributes.get(AttributeKey.stringKey("step")) }.toSet()
        assertEquals(setOf(STEP_STATUS, STEP_CLEAR, STEP_AUTHENTICATE, STEP_SUBJECT_TOKEN, STEP_VERIFY, STEP_TOTAL), steps)
        assertEquals("s", metric("zeta.probe.duration").unit)

        assertEquals(1L, metric("zeta.probe.up").longGaugeData.points.single().value)
        assertTrue(metric("zeta.probe.last_success.time").longGaugeData.points.single().value > 0)
        assertEquals(sdk.auth.exp, metric("zeta.probe.token.expiry.time").longGaugeData.points.single().value)
        assertEquals(2L, metric("zeta.probe.targets").longGaugeData.points.single().value)

        val finished = spans.finishedSpanItems
        val root = finished.single { it.name == "zeta.probe.login" }
        assertEquals("ok", root.attributes.get(AttributeKey.stringKey("zeta.result")))
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
        val target = ProbeTarget("https://a/", listOf("s"))
        val sdk = FakeSdk(FakeAuth(), counting, exchangeFails = true)
        val outcome = runProbe(ProbeKind.LOGIN, fakeSession(sdk, counting, target), 10.seconds, telemetry.spanScope(ProbeKind.LOGIN, target))
        telemetry.record(outcome)

        assertEquals(0L, metric("zeta.probe.up").longGaugeData.points.single().value)
        val runs = metric("zeta.probe.runs").longSumData.points.single()
        assertEquals("error", runs.attributes.get(AttributeKey.stringKey("result")))
        assertEquals("IllegalStateException", runs.attributes.get(AttributeKey.stringKey("error_type")))
        val root = spans.finishedSpanItems.single { it.name == "zeta.probe.login" }
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, root.status.statusCode)

        telemetry.forget(target)
        assertTrue(reader.collectAllMetrics().none { it.name == "zeta.probe.up" && it.longGaugeData.points.isNotEmpty() })
    }
}
