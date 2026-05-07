package de.gematik.zeta.cli.popp

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PoppMessagesTest {
    private val json = Json { ignoreUnknownKeys = true }

    // ───── StartMessage ────────────────────────────────────────────────────

    @Test
    fun `StartMessage encodes the default version (popp rejects missing version)`() {
        val text = json.encodeToString<PoppMessage>(
            StartMessage(cardConnectionType = "contact-connector", clientSessionId = "abc"),
        )
        assertTrue("\"version\":\"1.0.0\"" in text, "expected version on the wire, got: $text")
        assertTrue("\"type\":\"Start\"" in text)
        assertTrue("\"cardConnectionType\":\"contact-connector\"" in text)
        assertTrue("\"clientSessionId\":\"abc\"" in text)
    }

    @Test
    fun `StartMessage round-trips through the polymorphic serializer`() {
        val original = StartMessage(
            version = "1.0.0",
            cardConnectionType = "contactless-connector",
            clientSessionId = "session-uuid",
        )
        val text = json.encodeToString<PoppMessage>(original)
        val decoded = json.decodeFromString<PoppMessage>(text)
        assertEquals(original, decoded)
    }

    // ───── ConnectorScenarioMessage ────────────────────────────────────────

    @Test
    fun `ConnectorScenarioMessage decodes from server payload`() {
        val wire = """
            {
              "type": "ConnectorScenario",
              "version": "1.0.0",
              "signedScenario": "eyJhbGciOiJFUzI1NiJ9.payload.sig"
            }
        """.trimIndent()
        val decoded = json.decodeFromString<PoppMessage>(wire)
        assertInstanceOf(ConnectorScenarioMessage::class.java, decoded)
        decoded as ConnectorScenarioMessage
        assertEquals("1.0.0", decoded.version)
        assertEquals("eyJhbGciOiJFUzI1NiJ9.payload.sig", decoded.signedScenario)
    }

    // ───── StandardScenarioMessage ─────────────────────────────────────────

    @Test
    fun `StandardScenarioMessage decodes nested ScenarioStep array`() {
        val wire = """
            {
              "type": "StandardScenario",
              "version": "1.0.0",
              "clientSessionId": "abc",
              "sequenceCounter": 0,
              "timeSpan": 0,
              "steps": [
                { "commandApdu": "00A40000", "expectedStatusWords": ["9000"] },
                { "commandApdu": "00B00000", "expectedStatusWords": ["9000", "6A82"] }
              ]
            }
        """.trimIndent()
        val decoded = json.decodeFromString<PoppMessage>(wire)
        assertInstanceOf(StandardScenarioMessage::class.java, decoded)
        decoded as StandardScenarioMessage
        assertEquals(2, decoded.steps.size)
        assertEquals("00A40000", decoded.steps[0].commandApdu)
        assertEquals(listOf("9000", "6A82"), decoded.steps[1].expectedStatusWords)
    }

    @Test
    fun `StandardScenarioMessage tolerates omitted steps and expectedStatusWords`() {
        val wire = """
            {
              "type": "StandardScenario",
              "version": "1.0.0",
              "clientSessionId": "abc",
              "sequenceCounter": 1,
              "timeSpan": 5000
            }
        """.trimIndent()
        val decoded = json.decodeFromString<PoppMessage>(wire) as StandardScenarioMessage
        assertEquals(emptyList<ScenarioStep>(), decoded.steps)
    }

    // ───── ScenarioResponseMessage ─────────────────────────────────────────

    @Test
    fun `ScenarioResponseMessage round-trips`() {
        val original = ScenarioResponseMessage(steps = listOf("9000", "abcd9000", "6A81"))
        val text = json.encodeToString<PoppMessage>(original)
        assertTrue("\"type\":\"ScenarioResponse\"" in text)
        assertTrue("\"steps\":[\"9000\",\"abcd9000\",\"6A81\"]" in text)
        assertEquals(original, json.decodeFromString<PoppMessage>(text))
    }

    // ───── TokenMessage ────────────────────────────────────────────────────

    @Test
    fun `TokenMessage decodes from terminal server payload`() {
        val wire = """{"type":"Token","token":"eyJhbGciOiJFUzI1NiJ9.payload.sig"}"""
        val decoded = json.decodeFromString<PoppMessage>(wire) as TokenMessage
        assertEquals("eyJhbGciOiJFUzI1NiJ9.payload.sig", decoded.token)
    }

    // ───── ErrorMessage ────────────────────────────────────────────────────

    @Test
    fun `ErrorMessage decodes with errorDetail`() {
        val wire = """
            {
              "type": "Error",
              "errorCode": "WORKFLOW_NOT_SUPPORTED",
              "errorDetail": "Message is not supported"
            }
        """.trimIndent()
        val decoded = json.decodeFromString<PoppMessage>(wire) as ErrorMessage
        assertEquals("WORKFLOW_NOT_SUPPORTED", decoded.errorCode)
        assertEquals("Message is not supported", decoded.errorDetail)
    }

    @Test
    fun `ErrorMessage decodes without optional errorDetail`() {
        val wire = """{"type":"Error","errorCode":"INTERNAL"}"""
        val decoded = json.decodeFromString<PoppMessage>(wire) as ErrorMessage
        assertEquals("INTERNAL", decoded.errorCode)
        assertEquals(null, decoded.errorDetail)
    }

    // ───── Polymorphic dispatch + bad input ────────────────────────────────

    @Test
    fun `unknown discriminator is rejected`() {
        val wire = """{"type":"DefinitelyNotAPoppMessage","foo":"bar"}"""
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<PoppMessage>(wire)
        }
    }

    @Test
    fun `ignoreUnknownKeys lets future server fields land without breaking decode`() {
        val wire = """
            {
              "type": "Token",
              "token": "eyJ.payload.sig",
              "issuedAt": "2026-05-07T00:00:00Z"
            }
        """.trimIndent()
        val decoded = json.decodeFromString<PoppMessage>(wire) as TokenMessage
        assertEquals("eyJ.payload.sig", decoded.token)
    }
}
