package de.gematik.zeta.cli.state

import de.gematik.zeta.sdk.SdkStatus
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationResponse
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EntryRendererTest {

    @Test
    fun `annotates expired access token in status and expires-at lines`() {
        val nowEpoch = System.currentTimeMillis() / 1000
        val entry = Entry(
            resource = "https://example.com",
            issuer = "https://example.com/auth",
            status = SdkStatus.HAS_REFRESH_TOKEN,
            accessToken = TokenInfo(
                rawJwt = "x.y.z",
                header = JsonObject(emptyMap()),
                claims = JsonObject(emptyMap()),
                expiresAt = nowEpoch - 300,
            ),
            registration = RegistrationInfo(ClientRegistrationResponse(clientId = "cid")),
        )

        val out = renderEntryText(entry, colorize = false, reveal = false)

        assertTrue(out.contains("HAS_REFRESH_TOKEN (access token expired 5m ago)")) {
            "Status line should annotate expired AT; got:\n$out"
        }
        assertTrue(out.contains("(expired)")) {
            "Expires at line should include (expired); got:\n$out"
        }
    }

    @Test
    fun `does not annotate when access token is still valid`() {
        val nowEpoch = System.currentTimeMillis() / 1000
        val entry = Entry(
            resource = "https://example.com",
            issuer = "https://example.com/auth",
            status = SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN,
            accessToken = TokenInfo(
                rawJwt = "x.y.z",
                header = JsonObject(emptyMap()),
                claims = JsonObject(emptyMap()),
                expiresAt = nowEpoch + 600,
            ),
            registration = RegistrationInfo(ClientRegistrationResponse(clientId = "cid")),
        )

        val out = renderEntryText(entry, colorize = false, reveal = false)

        assertFalse(out.contains("(access token expired")) { "Status should have no expiry annotation:\n$out" }
        assertFalse(out.contains("(expired)")) { "Expires at should have no (expired):\n$out" }
    }
}
