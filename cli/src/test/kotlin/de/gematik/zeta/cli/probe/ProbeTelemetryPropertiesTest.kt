package de.gematik.zeta.cli.probe

import com.github.ajalt.clikt.core.UsageError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProbeTelemetryPropertiesTest {
    private val base = TelemetryOptions(env = "dev")
    private val nothingSet: (String) -> String? = { null }

    @Test
    fun `defaults expose prometheus and keep otlp on http`() {
        val d = otelDefaultProperties(base, "1.2.3")
        assertEquals("prometheus", d["otel.metrics.exporter"])
        assertEquals("9464", d["otel.exporter.prometheus.port"])
        assertEquals("0.0.0.0", d["otel.exporter.prometheus.host"])
        assertEquals("http/protobuf", d["otel.exporter.otlp.protocol"])
        assertEquals("none", d["otel.logs.exporter"])
        assertEquals("service.version=1.2.3,deployment.environment.name=dev", d["otel.resource.attributes"])
        assertFalse("otel.traces.exporter" in d, "the traces exporter is decided by the overrides")
    }

    @Test
    fun `traces are off without an endpoint`() {
        assertEquals("none", otelOverrideProperties(base, nothingSet)["otel.traces.exporter"])
    }

    @Test
    fun `an endpoint flag turns traces on`() {
        val o = otelOverrideProperties(base.copy(otlpEndpoint = "https://c:4318"), nothingSet)
        assertEquals("otlp", o["otel.traces.exporter"])
        assertEquals("https://c:4318", o["otel.exporter.otlp.endpoint"])
    }

    @Test
    fun `an endpoint from the environment turns traces on`() {
        val lookup: (String) -> String? = { if (it == "otel.exporter.otlp.endpoint") "https://c:4318" else null }
        assertEquals("otlp", otelOverrideProperties(base, lookup)["otel.traces.exporter"])
        val signalLookup: (String) -> String? = { if (it == "otel.exporter.otlp.traces.endpoint") "https://c:4318/v1/traces" else null }
        assertEquals("otlp", otelOverrideProperties(base, signalLookup)["otel.traces.exporter"])
    }

    @Test
    fun `an explicit traces exporter is left alone`() {
        val lookup: (String) -> String? = { if (it == "otel.traces.exporter") "logging" else null }
        assertNull(otelOverrideProperties(base.copy(otlpEndpoint = "https://c"), lookup)["otel.traces.exporter"])
    }

    @Test
    fun `flags override host port and protocol`() {
        val o = otelOverrideProperties(base.copy(metricsHost = "127.0.0.1", metricsPort = 9999, otlpProtocol = "grpc"), nothingSet)
        assertEquals("127.0.0.1", o["otel.exporter.prometheus.host"])
        assertEquals("9999", o["otel.exporter.prometheus.port"])
        assertEquals("grpc", o["otel.exporter.otlp.protocol"])
    }

    @Test
    fun `header flags are appended to the env list`() {
        assertEquals("a=b,Authorization=Bearer x", mergeOtlpHeaders("a=b", listOf("Authorization=Bearer x")))
        assertEquals("k=v=w", mergeOtlpHeaders(null, listOf("k=v=w")))
        assertEquals("a=b", mergeOtlpHeaders("a=b", emptyList()))
        assertNull(mergeOtlpHeaders("", emptyList()))
        assertThrows(UsageError::class.java) { mergeOtlpHeaders(null, listOf("novalue")) }
        assertThrows(UsageError::class.java) { mergeOtlpHeaders(null, listOf("=x")) }
    }
}
