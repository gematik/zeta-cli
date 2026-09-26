package de.gematik.zeta.cli.probe

import com.github.ajalt.clikt.core.UsageError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

class ProbeTelemetryPropertiesTest {
    private val base = TelemetryOptions(envs = listOf("dev"), instanceId = "host-1")
    private val nothingSet: (String) -> String? = { null }
    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = { pairs.toMap()[it] }

    @Test
    fun `defaults push both signals via otlp over http with cumulative temporality`() {
        val d = otelDefaultProperties()
        assertEquals("otlp", d["otel.metrics.exporter"])
        assertEquals("otlp", d["otel.traces.exporter"])
        assertEquals("none", d["otel.logs.exporter"])
        assertEquals("30000", d["otel.metric.export.interval"])
        assertEquals("http/protobuf", d["otel.exporter.otlp.protocol"])
        assertEquals("cumulative", d["otel.exporter.otlp.metrics.temporality.preference"])
        assertEquals("zeta-probe", d["otel.service.name"])
        assertFalse(d.keys.any { "prometheus" in it })
    }

    @Test
    fun `resource attributes carry version, environment and instance id`() {
        val o = otelOverrideProperties(base, "1.2.3", nothingSet)
        assertEquals("service.version=1.2.3,deployment.environment.name=dev,service.instance.id=host-1", o["otel.resource.attributes"])
    }

    @Test
    fun `several environments drop the deployment environment resource attribute`() {
        val o = otelOverrideProperties(base.copy(envs = listOf("dev", "ref")), "1.2.3", nothingSet)
        assertEquals("service.version=1.2.3,service.instance.id=host-1", o["otel.resource.attributes"])
    }

    @Test
    fun `user resource attributes win and keep the rest of ours`() {
        val o = otelOverrideProperties(base, "1.2.3", env("otel.resource.attributes" to "service.instance.id=pod-7, team=ops"))
        assertEquals("service.instance.id=pod-7,team=ops,service.version=1.2.3,deployment.environment.name=dev", o["otel.resource.attributes"])
    }

    @Test
    fun `flags override endpoint and protocol`() {
        val o = otelOverrideProperties(base.copy(otlpEndpoint = "https://c:4318", otlpProtocol = "http/protobuf"), "1", nothingSet)
        assertEquals("https://c:4318", o["otel.exporter.otlp.endpoint"])
        assertEquals("http/protobuf", o["otel.exporter.otlp.protocol"])
        assertFalse("otel.traces.exporter" in o)
    }

    @Test
    fun `an endpoint is required`() {
        val e = assertThrows(UsageError::class.java) { validateOtelConfig(base, nothingSet) }
        assertEquals("no OTLP endpoint: set OTEL_EXPORTER_OTLP_ENDPOINT or --otlp-endpoint", e.message)
        assertDoesNotThrow { validateOtelConfig(base.copy(otlpEndpoint = "http://c:4318"), nothingSet) }
        assertDoesNotThrow { validateOtelConfig(base, env("otel.exporter.otlp.endpoint" to "http://c:4318")) }
        assertDoesNotThrow {
            validateOtelConfig(base, env("otel.exporter.otlp.metrics.endpoint" to "http://m/v1/metrics", "otel.exporter.otlp.traces.endpoint" to "http://t/v1/traces"))
        }
        assertThrows(UsageError::class.java) { validateOtelConfig(base, env("otel.exporter.otlp.metrics.endpoint" to "http://m/v1/metrics")) }
    }

    @Test
    fun `a signal switched off needs no endpoint`() {
        assertDoesNotThrow {
            validateOtelConfig(base, env("otel.traces.exporter" to "none", "otel.exporter.otlp.metrics.endpoint" to "http://m/v1/metrics"))
        }
        assertDoesNotThrow { validateOtelConfig(base, env("otel.traces.exporter" to "none", "otel.metrics.exporter" to "none")) }
    }

    @Test
    fun `exporters other than otlp and none are refused before autoconfigure`() {
        val withEndpoint = base.copy(otlpEndpoint = "http://c:4318")
        val e = assertThrows(UsageError::class.java) { validateOtelConfig(withEndpoint, env("otel.metrics.exporter" to "prometheus")) }
        assertEquals("unsupported metrics exporter 'prometheus': zeta probe pushes via OTLP (allowed: otlp, none)", e.message)
        assertThrows(UsageError::class.java) { validateOtelConfig(withEndpoint, env("otel.traces.exporter" to "zipkin")) }
        assertThrows(UsageError::class.java) { validateOtelConfig(withEndpoint, env("otel.metrics.exporter" to "otlp,console")) }
        assertDoesNotThrow { validateOtelConfig(withEndpoint, env("otel.metrics.exporter" to "otlp", "otel.traces.exporter" to "none")) }
    }

    @Test
    fun `grpc is refused because the jdk sender speaks http only`() {
        val withEndpoint = base.copy(otlpEndpoint = "http://c:4318")
        assertThrows(UsageError::class.java) { validateOtelConfig(withEndpoint, env("otel.exporter.otlp.protocol" to "grpc")) }
        assertThrows(UsageError::class.java) { validateOtelConfig(withEndpoint, env("otel.exporter.otlp.traces.protocol" to "grpc")) }
        assertDoesNotThrow { validateOtelConfig(withEndpoint, env("otel.exporter.otlp.protocol" to "http/protobuf")) }
    }

    @Test
    fun `env lookup maps property keys to OTEL variables`() {
        assertNull(otelEnvLookup("otel.zeta.test.never.set"))
        System.setProperty("otel.zeta.test.key", "v")
        try {
            assertEquals("v", otelEnvLookup("otel.zeta.test.key"))
        } finally {
            System.clearProperty("otel.zeta.test.key")
        }
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

    @Test
    fun `merging resource attributes ignores blanks`() {
        assertEquals("a=1,b=2", mergeResourceAttributes(" a=1 ,, ", mapOf("a" to "x", "b" to "2")))
        assertTrue(mergeResourceAttributes(null, emptyMap()).isEmpty())
    }
}
