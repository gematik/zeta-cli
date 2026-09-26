package de.gematik.connector

import de.gematik.connector.api.gematik.conn.cardservicecommon20.CardType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail

/**
 * Pins the exact bytes the connector sends for the operations zeta depends on, so a change to the
 * XML format configuration (declaration, indentation, XML version, namespace prefixes) cannot reach a
 * Konnektor unnoticed. The goldens live in `src/test/resources/pinned-requests/`; on a mismatch the
 * actual bytes are written to `build/pinned-requests-actual/` for diffing.
 */
class RequestEncodingTest {
    private val sds = javaClass.getResource("/connector.sds")!!.readText()

    /** A Konnektor that records every SOAP request and answers each with an HTTP 500 the client cannot decode. */
    private fun recordingClient(recorded: MutableMap<String, String>): ConnectorClient = runBlocking {
        val engine = MockEngine { req ->
            if (req.method == HttpMethod.Get && req.url.encodedPath.endsWith("/connector.sds")) {
                respond(sds, HttpStatusCode.OK, headersOf("Content-Type", "text/xml"))
            } else {
                recorded[req.headers["SOAPAction"] ?: req.url.encodedPath] = req.body.toByteArray().toString(Charsets.UTF_8)
                respond("not soap", HttpStatusCode.InternalServerError)
            }
        }
        val dotkon = Dotkon(
            url = "https://konnektor.test",
            mandantId = "Mandant-1", workplaceId = "Workplace-1", clientSystemId = "ClientSystem-1",
            credentials = Credentials.Basic("u", "p"),
        )
        ConnectorClient.connect(HttpClient(engine), dotkon)
    }

    private fun sent(block: suspend ConnectorClient.() -> Unit): String {
        val recorded = linkedMapOf<String, String>()
        val client = recordingClient(recorded)
        runCatching { runBlocking { client.block() } }
        return recorded.values.singleOrNull() ?: fail("expected exactly one SOAP request, got ${recorded.keys}")
    }

    private fun assertPinned(name: String, actual: String) {
        val expected = javaClass.getResource("/pinned-requests/$name.xml")?.readText()
        if (expected != actual) {
            val out = Path.of("build/pinned-requests-actual/$name.xml")
            Files.createDirectories(out.parent)
            Files.writeString(out, actual)
            if (expected == null) fail("no golden for $name; actual written to ${out.toAbsolutePath()}")
            assertEquals(expected, actual, "$name request bytes changed; actual written to ${out.toAbsolutePath()}")
        }
    }

    @Test
    fun `GetCards request is byte-identical`() =
        assertPinned("GetCards", sent { getCardsByType(listOf(CardType.SmcB)) })

    @Test
    fun `ReadCardCertificate request is byte-identical`() =
        assertPinned("ReadCardCertificate", sent { readCardAutCertificate("card-handle-1") })

    @Test
    fun `ExternalAuthenticate request is byte-identical`() =
        assertPinned("ExternalAuthenticate", sent { externalAuthenticate("card-handle-1", ByteArray(32) { it.toByte() }) })

    @Test
    fun `StartCardSession request is byte-identical`() =
        assertPinned("StartCardSession", sent { startCardSession("card-handle-1") })

    @Test
    fun `requests carry no XML declaration and no indentation`() {
        val body = sent { readCardAutCertificate("card-handle-1") }
        assertFalse(body.startsWith("<?xml"), body.take(60))
        assertFalse('\n' in body, "request is pretty-printed")
        assertTrue(body.startsWith("<"), body.take(60))
    }

    @Serializable
    @XmlSerialName("Flag", namespace = "urn:zeta:test", prefix = "t")
    private data class Flag(
        @XmlElement(true) @XmlSerialName("Value", namespace = "urn:zeta:test", prefix = "t") val value: Boolean,
    )

    private fun decodeFlag(raw: String): Boolean =
        defaultXml.decodeFromString(Flag.serializer(), """<t:Flag xmlns:t="urn:zeta:test"><t:Value>$raw</t:Value></t:Flag>""").value

    @Test
    fun `xs boolean lexical forms decode as the schema defines them`() {
        assertTrue(decodeFlag("true"))
        assertTrue(decodeFlag("1"))
        assertFalse(decodeFlag("false"))
        assertFalse(decodeFlag("0"))
    }

    @Test
    fun `surrounding whitespace is collapsed like xs boolean requires`() {
        assertTrue(decodeFlag(" true "))
        assertTrue(decodeFlag("\n  1\n"))
        assertFalse(decodeFlag(" false "))
    }

    @Test
    fun `forms outside xs boolean are rejected`() {
        listOf("TRUE", "True", "yes", "").forEach { raw ->
            assertThrows<Exception>("'$raw' should not decode") { decodeFlag(raw) }
        }
    }
}
