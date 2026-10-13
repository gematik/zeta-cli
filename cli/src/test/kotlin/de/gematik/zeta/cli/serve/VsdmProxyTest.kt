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
package de.gematik.zeta.cli.serve

import de.gematik.zeta.catalog.Environment
import de.gematik.zeta.stress.identity.PoppClaims
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

    @Test
    fun `middleware headers name the patient, the cache outcome and the real upstream status`() {
        val claims = PoppClaims(
            actorId = "5-2-KHAUS-1",
            patientId = "X110411675",
            insurerId = "101575519",
            proofTime = null,
            iat = null,
            kid = null,
            iss = "https://popp.dev.poppservice.de/",
        )

        val hit = middlewareResponseHeaders(claims, "tok", cacheOutcome = "hit", upstreamStatus = 304).toMap()
        assertEquals("101575519", hit["middleware-insurer-id"])
        assertEquals("X110411675", hit["middleware-insurant-id"])
        assertEquals("tok", hit["middleware-popp"])
        assertEquals("hit", hit["middleware-cache"])
        assertEquals("304", hit["middleware-upstream-status"], "the client sees 200, so say what really happened")
        assertNull(hit["middleware-error-source"])

        val plain = middlewareResponseHeaders(claims, "tok", cacheOutcome = "miss", upstreamStatus = 200).toMap()
        assertEquals("200", plain["middleware-upstream-status"], "set on every response, not just on hits")

        val failed = middlewareResponseHeaders(claims, "tok", cacheOutcome = "bypass", upstreamStatus = 428).toMap()
        assertEquals("upstream", failed["middleware-error-source"])
    }
}
