package de.gematik.zeta.cli.probe

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProbeDurationTest {
    @Test
    fun `accepts the compact forms and ISO`() {
        assertEquals(3.hours, parseProbeDuration("3h"))
        assertEquals(5.minutes, parseProbeDuration("5m"))
        assertEquals(90.seconds, parseProbeDuration("90s"))
        assertEquals(90.minutes, parseProbeDuration("1h30m"))
        assertEquals(3.hours, parseProbeDuration("PT3H"))
        assertEquals(Duration.ZERO, parseProbeDuration("0"))
        assertEquals(Duration.ZERO, parseProbeDuration("off"))
        assertEquals(Duration.ZERO, parseProbeDuration(" OFF "))
    }

    @Test
    fun `rejects garbage and negatives`() {
        assertThrows(IllegalArgumentException::class.java) { parseProbeDuration("abc") }
        assertThrows(IllegalArgumentException::class.java) { parseProbeDuration("-5m") }
        assertThrows(IllegalArgumentException::class.java) { parseProbeDuration("") }
    }
}
