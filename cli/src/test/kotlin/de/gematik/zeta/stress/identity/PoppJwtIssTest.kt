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
package de.gematik.zeta.stress.identity

import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PoppJwtIssTest {

    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return "${enc.encodeToString("""{"kid":"k1"}""".toByteArray())}.${enc.encodeToString(payload.toByteArray())}.sig"
    }

    @Test
    fun `decodes the iss claim`() {
        val token = jwt(
            """{"actorId":"5-2-KH-ZETA","patientId":"T012345678","insurerId":"101575519",""" +
                """"iss":"https://popp.dev.poppservice.de/x"}""",
        )
        val claims = PoppJwt.parse(token)
        assertEquals("https://popp.dev.poppservice.de/x", claims?.iss)
        assertEquals("101575519", claims?.insurerId)
    }

    @Test
    fun `iss is null when absent`() {
        val token = jwt("""{"actorId":"a","patientId":"p","insurerId":"i"}""")
        assertNull(PoppJwt.parse(token)?.iss)
    }
}
