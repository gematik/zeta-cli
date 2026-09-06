package de.gematik.zeta.cli.vsdm

import de.gematik.zeta.catalog.Environment
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
        env: Environment = Environment.DEV,
        origin: String = "https://vsdm-dev.tk.de",
        profileVersion: String = "1.1",
        contentType: String = "application/fhir+json",
    ) = cacheKeyFor(claims, env, origin, profileVersion, contentType)

    @Test
    fun `key carries insurer and insurant, not the reading actor`() {
        val k = key()
        assertEquals("101575519", k.insurerId)
        assertEquals("X110411675", k.patientId)
        assertEquals(key(claims = claims.copy(actorId = "5-2-KHAUS-2")), k)
        assertEquals(key(claims = claims.copy(proofTime = 1, iat = 2, kid = "other")), k)
    }

    @Test
    fun `endpoint, environment, profile version and content type each separate entries`() {
        val base = key()
        assertNotEquals(base, key(env = Environment.REF))
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
