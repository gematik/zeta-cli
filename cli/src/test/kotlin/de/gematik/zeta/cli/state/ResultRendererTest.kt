/*
 * Copyright (Change Date see Readme), gematik GmbH
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the
 * European Commission – subsequent versions of the EUPL (the "Licence").
 * You may not use this work except in compliance with the Licence.
 *
 * You find a copy of the Licence in the "Licence" file or at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.
 * In case of changes by gematik GmbH find details in the "Readme" file.
 *
 * See the Licence for the specific language governing permissions and limitations under the Licence.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 */
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
