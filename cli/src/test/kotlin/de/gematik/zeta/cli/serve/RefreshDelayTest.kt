package de.gematik.zeta.cli.serve

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RefreshDelayTest {
    @Test
    fun `wakes lead-ms before expiry`() {
        // exp at 1000s, now at 900_000ms (=900s), lead 5s -> (1000_000 - 5000 - 900_000) = 95_000ms
        assertEquals(95_000L, refreshDelayMs(expSec = 1000, nowMs = 900_000, leadMs = 5_000))
    }

    @Test
    fun `floors at zero when the lead window has already passed`() {
        // now is inside the lead window (exp-lead already reached) -> 0, refresh immediately
        assertEquals(0L, refreshDelayMs(expSec = 1000, nowMs = 999_000, leadMs = 5_000))
    }

    @Test
    fun `floors at zero when the token is already expired`() {
        assertEquals(0L, refreshDelayMs(expSec = 1000, nowMs = 2_000_000, leadMs = 5_000))
    }
}
