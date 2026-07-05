package de.gematik.zeta.catalog

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private val SAMPLE = """
    {
      "format_version": "1.0",
      "updated_at": 1780412166,
      "env": "dev",
      "service_instances": {
        "vsdm-1": { "type": "vsdm", "url": "https://vsdm-dev.tk.de" },
        "vsdm-2": { "type": "vsdm", "url": "https://vsdm-dev.other.de" }
      },
      "routing": { "vsdm": { "101575519": "vsdm-1", "888": "vsdm-missing" } }
    }
""".trimIndent()

class CatalogTest {

    @Test
    fun `environment from issuer matches the host infix`() {
        assertEquals(Environment.DEV, environmentFromIssuer("https://popp.dev.poppservice.de/x"))
        assertEquals(Environment.REF, environmentFromIssuer("https://popp.ref.poppservice.de/x"))
        assertEquals(Environment.TEST, environmentFromIssuer("https://popp.test.poppservice.de/x"))
        assertEquals(Environment.PROD, environmentFromIssuer("https://popp.prod.poppservice.de/x"))
    }

    @Test
    fun `environment is null when no infix matches`() {
        assertNull(environmentFromIssuer("https://popp.poppservice.de/x"))
        assertNull(environmentFromIssuer("not-a-url"))
    }

    @Test
    fun `catalog host and url`() {
        assertEquals("service-discovery.dev.ti-platform.de", Environment.DEV.host)
        assertEquals("https://service-discovery.prod.ti-platform.de/catalog.json", Environment.PROD.catalogUrl)
    }

    @Test
    fun `vsdmBaseUrl resolves a routed IKNR`() {
        val catalog = ServiceCatalog(
            serviceInstances = mapOf("vsdm-1" to ServiceInstance("vsdm", "https://vsdm-dev.tk.de")),
            routing = mapOf("vsdm" to mapOf("101575519" to "vsdm-1")),
        )
        assertEquals("https://vsdm-dev.tk.de", catalog.vsdmBaseUrl("101575519"))
    }

    @Test
    fun `vsdmBaseUrl is null for an unrouted IKNR or a missing instance`() {
        val catalog = ServiceCatalog(
            serviceInstances = mapOf("vsdm-1" to ServiceInstance("vsdm", "https://vsdm-dev.tk.de")),
            routing = mapOf("vsdm" to mapOf("101575519" to "vsdm-1", "888" to "vsdm-missing")),
        )
        assertNull(catalog.vsdmBaseUrl("000000000"))
        assertNull(catalog.vsdmBaseUrl("888"))
    }

    @Test
    fun `fetchCatalog parses and resolves via an injected client`() {
        val engine = MockEngine {
            respond(SAMPLE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val catalog = runBlocking { ServiceDiscoveryClient(HttpClient(engine)).fetchCatalog(Environment.DEV) }
        assertEquals("dev", catalog.env)
        assertEquals("https://vsdm-dev.tk.de", catalog.vsdmBaseUrl("101575519"))
    }

    @Test
    fun `fetchCatalog raises CatalogException on a non-2xx response`() {
        val engine = MockEngine { respond("nope", HttpStatusCode.NotFound) }
        assertThrows<CatalogException> {
            runBlocking { ServiceDiscoveryClient(HttpClient(engine)).fetchCatalog(Environment.DEV) }
        }
    }
}
