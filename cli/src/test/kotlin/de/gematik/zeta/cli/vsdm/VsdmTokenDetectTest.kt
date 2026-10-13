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
