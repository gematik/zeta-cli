package de.gematik.zeta.cli.probe

import de.gematik.zeta.catalog.Environment
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProbeBackoffTest {
    private val a = ProbeTarget(Environment.DEV, "https://a/", listOf("s"), "a", "a")
    private val b = ProbeTarget(Environment.DEV, "https://b/", listOf("s"), "b", "b")
    private val interval = 20.seconds
    private fun policy(max: Duration = 1.hours) = BackoffPolicy(max) { kind -> if (kind == ProbeKind.LOGIN) 1.minutes else interval }
    private val i = interval.inWholeMilliseconds

    @Test
    fun `disabled policy never holds anything back`() {
        val p = policy(Duration.ZERO)
        assertFalse(p.enabled)
        p.record(ProbeKind.REFRESH, a, ok = false, nowMs = 0)
        assertTrue(p.shouldRun(ProbeKind.REFRESH, a.key, 1))
        assertTrue(p.entries().isEmpty())
    }

    @Test
    fun `one failure holds the target for twice the interval`() {
        val p = policy()
        assertTrue(p.shouldRun(ProbeKind.REFRESH, a.key, 0))
        p.record(ProbeKind.REFRESH, a, ok = false, nowMs = 1000)
        assertFalse(p.shouldRun(ProbeKind.REFRESH, a.key, 1000 + 2 * i - 1))
        assertTrue(p.shouldRun(ProbeKind.REFRESH, a.key, 1000 + 2 * i))
        assertEquals(listOf(BackoffEntry(ProbeKind.REFRESH, a, 1, 1000 + 2 * i)), p.entries())
    }

    @Test
    fun `consecutive failures double the hold-off up to the cap`() {
        val p = policy(max = 5.minutes)
        assertEquals(40.seconds, p.delayFor(ProbeKind.REFRESH, 1))
        assertEquals(80.seconds, p.delayFor(ProbeKind.REFRESH, 2))
        assertEquals(160.seconds, p.delayFor(ProbeKind.REFRESH, 3))
        assertEquals(5.minutes, p.delayFor(ProbeKind.REFRESH, 4))
        assertEquals(5.minutes, p.delayFor(ProbeKind.REFRESH, 40))
        assertEquals(2.minutes, p.delayFor(ProbeKind.LOGIN, 1))
        var now = 0L
        repeat(3) { p.record(ProbeKind.REFRESH, a, ok = false, nowMs = now); now += 1 }
        assertEquals(3, p.entries().single().failures)
        assertEquals(2 + 160_000L, p.entries().single().dueAtEpochMs)
    }

    @Test
    fun `a success clears the entry and restarts the sequence`() {
        val p = policy()
        p.record(ProbeKind.REFRESH, a, ok = false, nowMs = 0)
        p.record(ProbeKind.REFRESH, a, ok = false, nowMs = 0)
        p.record(ProbeKind.REFRESH, a, ok = true, nowMs = 0)
        assertTrue(p.entries().isEmpty())
        assertTrue(p.shouldRun(ProbeKind.REFRESH, a.key, 0))
        p.record(ProbeKind.REFRESH, a, ok = false, nowMs = 0)
        assertEquals(2 * i, p.entries().single().dueAtEpochMs)
    }

    @Test
    fun `kinds and targets are independent, forget clears both kinds`() {
        val p = policy()
        p.record(ProbeKind.LOGIN, a, ok = false, nowMs = 0)
        p.record(ProbeKind.REFRESH, a, ok = false, nowMs = 0)
        p.record(ProbeKind.REFRESH, b, ok = false, nowMs = 0)
        assertFalse(p.shouldRun(ProbeKind.REFRESH, a.key, 0))
        assertFalse(p.shouldRun(ProbeKind.LOGIN, a.key, 0))
        assertEquals(3, p.entries().size)
        p.forget(a)
        assertEquals(listOf(b), p.entries().map { it.target })
        assertTrue(p.shouldRun(ProbeKind.LOGIN, a.key, 0))
    }
}
