package de.gematik.connector

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DotkonTest {

    @Test
    fun `parses a basic-credentials dotkon`() {
        val dk = parseDotkon(
            """
            {
                "version": "1.0.0",
                "url": "https://konnektor.example.com:8443",
                "mandantId": "M1", "workplaceId": "W1",
                "clientSystemId": "C1", "userId": "U1",
                "credentials": { "type": "basic", "username": "user", "password": "secret" },
                "env": "ru",
                "insecureSkipVerify": true,
                "expectedHost": "konnektor.example.com"
            }
            """.trimIndent(),
            envLookup = { null },
        )
        assertEquals("https://konnektor.example.com:8443", dk.url)
        assertEquals("M1", dk.mandantId)
        assertEquals("ru", dk.env)
        assertTrue(dk.insecureSkipVerify)
        assertEquals("konnektor.example.com", dk.expectedHost)
        val basic = assertInstanceOf(Credentials.Basic::class.java, dk.credentials)
        assertEquals("user", basic.username)
        assertEquals("secret", basic.password)
    }

    @Test
    fun `expands environment variables`() {
        val dk = parseDotkon(
            """
            {
                "url": "https://k.example.com",
                "mandantId": "M1", "workplaceId": "W1", "clientSystemId": "C1",
                "credentials": { "type": "basic", "username": "u", "password": "${'$'}{KON_TEST_PWD}" }
            }
            """.trimIndent(),
            envLookup = { if (it == "KON_TEST_PWD") "env-secret-123" else null },
        )
        val basic = dk.credentials as Credentials.Basic
        assertEquals("env-secret-123", basic.password)
    }

    @Test
    fun `parses pkcs12 credentials`() {
        val dk = parseDotkon(
            """
            {
                "url": "https://k.example.com",
                "mandantId": "M1", "workplaceId": "W1", "clientSystemId": "C1",
                "credentials": { "type": "pkcs12", "data": "dGVzdC1wa2NzMTItZGF0YQ==", "password": "test" }
            }
            """.trimIndent(),
            envLookup = { null },
        )
        val pk = assertInstanceOf(Credentials.Pkcs12::class.java, dk.credentials)
        assertEquals("dGVzdC1wa2NzMTItZGF0YQ==", pk.data)
        assertEquals("test", pk.password)
    }

    @Test
    fun `pkcs12 password defaults to empty string`() {
        val dk = parseDotkon(
            """
            {
                "url": "https://k.example.com",
                "mandantId": "M1", "workplaceId": "W1", "clientSystemId": "C1",
                "credentials": { "type": "pkcs12", "data": "dGVzdA==" }
            }
            """.trimIndent(),
            envLookup = { null },
        )
        val pk = dk.credentials as Credentials.Pkcs12
        assertEquals("", pk.password)
    }

    @Test
    fun `rejects invalid env value`() {
        val ex = assertThrows(DotkonValidationException::class.java) {
            parseDotkon(
                """
                {
                    "url": "https://k.example.com",
                    "mandantId": "M1", "workplaceId": "W1", "clientSystemId": "C1",
                    "env": "invalid",
                    "credentials": { "type": "basic", "username": "u", "password": "p" }
                }
                """.trimIndent(),
                envLookup = { null },
            )
        }
        assertTrue(ex.message!!.contains("env"), "message: ${ex.message}")
    }

    @Test
    fun `rejects empty basic credentials`() {
        val ex = assertThrows(DotkonValidationException::class.java) {
            parseDotkon(
                """
                {
                    "url": "https://k.example.com",
                    "mandantId": "M1", "workplaceId": "W1", "clientSystemId": "C1",
                    "credentials": { "type": "basic", "username": "", "password": "" }
                }
                """.trimIndent(),
                envLookup = { null },
            )
        }
        val msg = ex.message!!
        assertTrue("credentials.username" in msg, "message: $msg")
        assertTrue("credentials.password" in msg, "message: $msg")
    }

    @Test
    fun `rejects pkcs12 without data`() {
        val ex = assertThrows(DotkonValidationException::class.java) {
            parseDotkon(
                """
                {
                    "url": "https://k.example.com",
                    "mandantId": "M1", "workplaceId": "W1", "clientSystemId": "C1",
                    "credentials": { "type": "pkcs12", "data": "" }
                }
                """.trimIndent(),
                envLookup = { null },
            )
        }
        assertTrue(ex.message!!.contains("credentials.data"), "message: ${ex.message}")
    }

    @Test
    fun `rejects unknown credentials type`() {
        val ex = assertThrows(DotkonValidationException::class.java) {
            parseDotkon(
                """
                {
                    "url": "https://k.example.com",
                    "mandantId": "M1", "workplaceId": "W1", "clientSystemId": "C1",
                    "credentials": { "type": "unknown" }
                }
                """.trimIndent(),
                envLookup = { null },
            )
        }
        // Whatever the wording, the error should mention basic/pkcs12 to point users at valid types.
        assertTrue(
            ex.message!!.contains("basic") && ex.message!!.contains("pkcs12"),
            "message: ${ex.message}",
        )
    }

    @Test
    fun `rewriteServiceEndpoints round-trips through JSON`() {
        val dk = parseDotkon(
            """
            {
                "url": "https://k.example.com",
                "rewriteServiceEndpoints": true,
                "mandantId": "M1", "workplaceId": "W1", "clientSystemId": "C1",
                "credentials": { "type": "basic", "username": "u", "password": "p" }
            }
            """.trimIndent(),
            envLookup = { null },
        )
        assertTrue(dk.rewriteServiceEndpoints)
    }
}
