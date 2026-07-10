package de.gematik.zeta.cli.state

import de.gematik.zeta.sdk.SdkStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResultRendererTest {

    @Test
    fun `login-style success needs a usable credential, not merely registration`() {
        // The verdict the user cares about: authenticated once *any* usable token exists.
        assertTrue(SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN.hasUsableCredentials)
        assertTrue(SdkStatus.HAS_REFRESH_TOKEN.hasUsableCredentials)
        assertFalse(SdkStatus.REGISTERED_NO_VALID_TOKENS.hasUsableCredentials)
        assertFalse(SdkStatus.NOT_REGISTERED.hasUsableCredentials)
    }

    @Test
    fun `registration verdict is independent of token state`() {
        assertFalse(SdkStatus.NOT_REGISTERED.isRegistered)
        assertTrue(SdkStatus.REGISTERED_NO_VALID_TOKENS.isRegistered)
        assertTrue(SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN.isRegistered)
    }

    @Test
    fun `text render leads with the verdict mark and shows the key facts`() {
        val ok = renderResultText(
            CommandResult(
                operation = "login",
                ok = true,
                endpoint = "https://example.com/",
                scopes = listOf("vsdservice", "openid"),
                authServer = "https://as.example.com/",
                status = SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN,
            ),
            colorize = false,
        )
        assertTrue(ok.startsWith("✓ login  https://example.com/"), ok)
        assertTrue(ok.contains("scopes:") && ok.contains("vsdservice openid"), ok)
        assertTrue(ok.contains("auth server:") && ok.contains("https://as.example.com/"), ok)
        assertTrue(ok.contains("status:") && ok.contains("HAS_ACCESS_AND_REFRESH_TOKEN"), ok)

        val bad = renderResultText(
            CommandResult(
                operation = "authenticate",
                ok = false,
                endpoint = "https://example.com/",
                scopes = emptyList(),
                authServer = null,
                status = SdkStatus.REGISTERED_NO_VALID_TOKENS,
                detail = "no valid tokens issued",
            ),
            colorize = false,
        )
        assertTrue(bad.startsWith("✗ authenticate"), bad)
        assertTrue(bad.contains("note:") && bad.contains("no valid tokens issued"), bad)
        assertFalse(bad.contains("scopes:"), bad) // empty scopes omitted
    }

    @Test
    fun `json render carries ok plus the structured facts`() {
        val json = renderResultJson(
            CommandResult(
                operation = "login",
                ok = true,
                endpoint = "https://example.com/",
                scopes = listOf("vsdservice"),
                authServer = "https://as.example.com/",
                status = SdkStatus.HAS_REFRESH_TOKEN,
            ),
        ) as JsonObject
        assertEquals(true, (json["ok"] as JsonPrimitive).booleanOrNull)
        assertEquals("login", (json["operation"] as JsonPrimitive).content)
        assertEquals("HAS_REFRESH_TOKEN", (json["status"] as JsonPrimitive).content)
    }
}
