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
package de.gematik.zeta.cli.vsdm

import de.gematik.zeta.stress.identity.PoppClaims
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VsdmCacheKeyTest {

    private val claims = PoppClaims(
        actorId = "5-2-KHAUS-1",
        patientId = "X110411675",
        insurerId = "101575519",
        proofTime = 1_788_243_600,
        iat = 1_788_243_600,
        kid = "abc",
        iss = "https://popp.dev.poppservice.de/",
    )

    private fun key(
        claims: PoppClaims = this.claims,
        origin: String = "https://vsdm-dev.tk.de",
        profileVersion: String = "1.1",
        contentType: String = "application/fhir+json",
    ) = cacheKeyFor(claims, origin, profileVersion, contentType)

    @Test
    fun `key carries the reading identity, the insurer and the insurant`() {
        val k = key()
        assertEquals("5-2-KHAUS-1", k.actorId)
        assertEquals("101575519", k.insurerId)
        assertEquals("X110411675", k.insurantId, "the token's patientId claim, named for what it is in VSDM")
    }

    @Test
    fun `two identities reading the same record get separate entries`() {
        assertNotEquals(key(), key(claims = claims.copy(actorId = "5-2-KHAUS-2")))
    }

    @Test
    fun `when the presence was proven does not separate entries`() {
        assertEquals(key(claims = claims.copy(proofTime = 1, iat = 2, kid = "other")), key())
    }

    @Test
    fun `endpoint, profile version and content type each separate entries`() {
        val base = key()
        assertNotEquals(base, key(origin = "https://vsdm-dev.other.de"))
        assertNotEquals(base, key(profileVersion = "1.0"))
        assertNotEquals(base, key(contentType = "application/fhir+xml"))
    }

    @Test
    fun `content type is normalized to the bare media type`() {
        assertEquals("application/fhir+json", normalizedContentType("application/fhir+json"))
        assertEquals("application/fhir+json", normalizedContentType("Application/FHIR+JSON; charset=utf-8"))
        assertEquals("application/fhir+json", normalizedContentType("application/fhir+json;q=0.9, application/fhir+xml"))
        assertEquals("application/cbor", normalizedContentType("  application/cbor  "))
    }

    @Test
    fun `a wildcard or missing accept pins no type`() {
        assertNull(normalizedContentType(null))
        assertNull(normalizedContentType(""))
        assertNull(normalizedContentType("*/*"))
        assertNull(normalizedContentType("application/*"))
    }
}
