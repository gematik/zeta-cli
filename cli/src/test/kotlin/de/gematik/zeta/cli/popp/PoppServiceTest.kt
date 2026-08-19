package de.gematik.zeta.cli.popp

import de.gematik.zeta.catalog.Environment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PoppServiceTest {
    @Test
    fun `dev url matches the historical DEFAULT_SERVICE_URL`() {
        assertEquals(
            "wss://popp.dev.poppservice.de/popp/practitioner/api/v1/token-generation-ehc",
            poppServiceUrlFor(Environment.DEV),
        )
    }

    @Test
    fun `host infix follows the environment`() {
        assertEquals("wss://popp.ref.poppservice.de$POPP_TOKEN_PATH", poppServiceUrlFor(Environment.REF))
        assertEquals("wss://popp.test.poppservice.de$POPP_TOKEN_PATH", poppServiceUrlFor(Environment.TEST))
        assertEquals("wss://popp.prod.poppservice.de$POPP_TOKEN_PATH", poppServiceUrlFor(Environment.PROD))
    }
}
