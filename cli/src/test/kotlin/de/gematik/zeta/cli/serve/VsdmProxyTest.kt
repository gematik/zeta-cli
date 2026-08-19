package de.gematik.zeta.cli.serve

import de.gematik.zeta.catalog.Environment
import io.ktor.http.headersOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VsdmProxyTest {
    @Test
    fun `env match yields no error, mismatch yields a 409 message`() {
        assertNull(envMismatchError(Environment.DEV, Environment.DEV))
        val msg = envMismatchError(Environment.REF, Environment.DEV)
        assertEquals("PoPP token is for 'ref', this daemon serves 'dev'", msg)
    }

    @Test
    fun `forwarded headers keep client headers but drop framing and transport auth`() {
        val headers = headersOf(
            "Accept" to listOf("application/fhir+json"),
            "If-None-Match" to listOf("\"abc\""),
            "PoPP" to listOf("eyJ…"),
            "Host" to listOf("localhost"),
            "Content-Length" to listOf("0"),
            "Authorization" to listOf("Bearer secret"),
            "Cookie" to listOf("session=1"),
            "Connection" to listOf("keep-alive"),
        )

        val forwarded = forwardableUpstreamHeaders(headers).toMap()

        assertEquals("application/fhir+json", forwarded["Accept"])
        assertEquals("\"abc\"", forwarded["If-None-Match"])
        assertEquals("eyJ…", forwarded["PoPP"])
        assertTrue(setOf("Host", "Content-Length", "Authorization", "Cookie", "Connection").none { it in forwarded })
    }

    @Test
    fun `middleware- control headers are consumed by the daemon, never forwarded upstream`() {
        val headers = headersOf(
            "Accept" to listOf("application/fhir+xml"),
            "middleware-egk" to listOf("SMC-B-235"),
            "Middleware-Popp" to listOf("should-not-leak"),
        )

        val forwarded = forwardableUpstreamHeaders(headers).toMap()

        assertEquals("application/fhir+xml", forwarded["Accept"])
        assertTrue(forwarded.keys.none { it.startsWith("middleware-", ignoreCase = true) })
    }

    @Test
    fun `multi-value client headers are all forwarded`() {
        val headers = headersOf("X-Trace" to listOf("a", "b"))
        val forwarded = forwardableUpstreamHeaders(headers)
        assertEquals(listOf("X-Trace" to "a", "X-Trace" to "b"), forwarded.filter { it.first == "X-Trace" })
    }

    @Test
    fun `upstream url appends the read path and forwards the query string verbatim`() {
        assertEquals(
            "https://vsdm.example.de/vsdservice/v1/vsdmbundle?profileVersion=1.1&foo=bar",
            upstreamVsdmUrl("https://vsdm.example.de/", "profileVersion=1.1&foo=bar"),
        )
    }

    @Test
    fun `upstream url with no query string omits the question mark`() {
        assertEquals(
            "https://vsdm.example.de/vsdservice/v1/vsdmbundle",
            upstreamVsdmUrl("https://vsdm.example.de", ""),
        )
    }
}
