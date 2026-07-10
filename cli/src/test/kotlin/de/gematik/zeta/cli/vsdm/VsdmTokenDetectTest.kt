package de.gematik.zeta.cli.vsdm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VsdmTokenDetectTest {

    @Test
    fun `compact JWTs are detected as tokens`() {
        // header.payload.signature, all base64url (unpadded).
        assertTrue(looksLikeJwt("eyJhbGciOiJFUzI1NiJ9.eyJhY3RvcklkIjoieCJ9.c2ln"))
        assertTrue(looksLikeJwt("aA0-_.bB1-_.cC2-_"))
    }

    @Test
    fun `file paths and non-JWT strings are not tokens`() {
        assertFalse(looksLikeJwt("popp.jwt")) // 2 segments
        assertFalse(looksLikeJwt("tokens/insurant-1.jwt")) // slash + 2 segments
        assertFalse(looksLikeJwt("dir/a.b.c")) // slash in a segment
        assertFalse(looksLikeJwt("./a.b.c")) // leading dot → empty first segment
        assertFalse(looksLikeJwt("a.b.")) // empty trailing segment
        assertFalse(looksLikeJwt("plain-string"))
        assertFalse(looksLikeJwt("/abs/path/token"))
    }
}
