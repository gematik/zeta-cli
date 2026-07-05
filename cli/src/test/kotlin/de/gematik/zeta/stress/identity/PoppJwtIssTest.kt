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
