package de.gematik.zeta.cli.probe

import de.gematik.zeta.catalog.Environment
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProbeSchedulerTest {
    private val a = ProbeTarget(Environment.DEV, "https://a/", listOf("s"), "a", "a")
    private val b = ProbeTarget(Environment.DEV, "https://b/", listOf("s"), "b", "b")
    private val c = ProbeTarget(Environment.DEV, "https://c/", listOf("s"), "c", "c")
    private val d = ProbeTarget(Environment.DEV, "https://d/", listOf("s"), "d", "d")

    private fun outcome(kind: ProbeKind, target: ProbeTarget) = ProbeOutcome(
        kind, target, ProbeResult.OK, null, null, false, false, null, null, emptyList(), Duration.ZERO, null,
    )

    @Test
    fun `spreads one interval evenly over the targets`() = runTest {
        val registry = TargetRegistry(listOf(a, b, c, d))
        val launches = mutableListOf<Pair<Long, ProbeTarget>>()
        val scheduler = ProbeScheduler(
            registry,
            { kind, target -> launches += testScheduler.currentTime to target; outcome(kind, target) },
            {},
            this,
        )
        val job = scheduler.start(ProbeKind.REFRESH, 40.seconds)
        advanceTimeBy(80.seconds)
        job!!.cancel()

        // 40s / 4 targets = one launch every 10s; each target exactly once per interval, in list order.
        assertEquals(listOf(0L, 10_000L, 20_000L, 30_000L, 40_000L, 50_000L, 60_000L, 70_000L), launches.map { it.first })
        assertEquals(listOf(a, b, c, d, a, b, c, d), launches.map { it.second })
    }

    @Test
    fun `picks up a target added mid-cycle`() = runTest {
        val registry = TargetRegistry(listOf(a, b))
        val launches = mutableListOf<ProbeTarget>()
        val scheduler = ProbeScheduler(registry, { kind, target -> launches += target; outcome(kind, target) }, {}, this)
        val job = scheduler.start(ProbeKind.LOGIN, 20.seconds)
        advanceTimeBy(15.seconds) // a@0, b@10
        registry.update(listOf(a, b, c))
        advanceTimeBy(30.seconds) // c@20, a@~26.7, b@~33.3, c@40
        job!!.cancel()
        assertEquals(listOf(a, b, c, a, b, c), launches)
    }

    @Test
    fun `a disabled interval starts nothing`() = runTest {
        val scheduler = ProbeScheduler(TargetRegistry(listOf(a)), { kind, target -> outcome(kind, target) }, {}, this)
        assertNull(scheduler.start(ProbeKind.LOGIN, Duration.ZERO))
    }

    @Test
    fun `a slow probe does not delay the next launch`() = runTest {
        val registry = TargetRegistry(listOf(a, b))
        val launches = mutableListOf<Long>()
        val scheduler = ProbeScheduler(
            registry,
            { kind, target ->
                launches += testScheduler.currentTime
                kotlinx.coroutines.delay(60.seconds)
                outcome(kind, target)
            },
            {},
            this,
        )
        val job = scheduler.start(ProbeKind.REFRESH, 20.seconds)
        advanceTimeBy(45.seconds)
        job!!.cancel()
        coroutineContext[kotlinx.coroutines.Job]!!.children.forEach { it.cancel() }
        assertEquals(listOf(0L, 10_000L, 20_000L, 30_000L, 40_000L), launches)
    }

    private fun failed(kind: ProbeKind, target: ProbeTarget) = outcome(kind, target).copy(result = ProbeResult.ERROR, errorType = "x")

    @Test
    fun `a failing target is skipped while backing off and the cadence holds`() = runTest {
        val registry = TargetRegistry(listOf(a, b))
        val launches = mutableListOf<Pair<Long, ProbeTarget>>()
        val scheduler = ProbeScheduler(
            registry,
            { kind, target -> launches += testScheduler.currentTime to target; if (target == a) failed(kind, target) else outcome(kind, target) },
            {},
            this,
            backoff = BackoffPolicy(1.hours) { 20.seconds },
            clock = { testScheduler.currentTime },
        )
        val job = scheduler.start(ProbeKind.REFRESH, 20.seconds)
        advanceTimeBy(130.seconds)
        job!!.cancel()
        // a fails at 0 (due 40s), runs at 40 (due 120s), runs at 120; b keeps its 10, 30, 50, … cadence throughout.
        assertEquals(listOf(0L, 40_000L, 120_000L), launches.filter { it.second == a }.map { it.first })
        assertEquals(listOf(10_000L, 30_000L, 50_000L, 70_000L, 90_000L, 110_000L), launches.filter { it.second == b }.map { it.first })
    }

    @Test
    fun `a recovered target and a crashing runner are handled`() = runTest {
        val registry = TargetRegistry(listOf(a))
        val launches = mutableListOf<Long>()
        var calls = 0
        val scheduler = ProbeScheduler(
            registry,
            { kind, target ->
                launches += testScheduler.currentTime
                calls++
                when (calls) {
                    1 -> failed(kind, target)
                    2 -> outcome(kind, target)
                    3 -> throw IllegalStateException("boom")
                    else -> outcome(kind, target)
                }
            },
            {},
            this,
            backoff = BackoffPolicy(1.hours) { 10.seconds },
            clock = { testScheduler.currentTime },
        )
        val job = scheduler.start(ProbeKind.LOGIN, 10.seconds)
        advanceTimeBy(75.seconds)
        job!!.cancel()
        // fail@0 → due 20; ok@20 → back to cadence; crash@30 → due 50; ok@50, ok@60, ok@70.
        assertEquals(listOf(0L, 20_000L, 30_000L, 50_000L, 60_000L, 70_000L), launches)
    }

    @Test
    fun `a ticking loop is live, an overdue or stopped one is not`() = runTest {
        val registry = TargetRegistry(listOf(a, b))
        val scheduler = ProbeScheduler(registry, { kind, target -> outcome(kind, target) }, {}, this, clock = { testScheduler.currentTime })
        val job = scheduler.start(ProbeKind.REFRESH, 20.seconds)
        advanceTimeBy(35.seconds)
        val now = testScheduler.currentTime
        assertEquals(emptyList<String>(), scheduler.livenessProblems(now, 60.seconds, 3.minutes))
        // next tick due at 40s; seen from 40s + 61s later it is overdue
        assertEquals(listOf("refresh loop overdue by 61s"), scheduler.livenessProblems(40_000 + 61_000, 60.seconds, 3.minutes))
        job!!.cancel()
        advanceTimeBy(1.seconds)
        assertEquals(listOf("refresh loop stopped"), scheduler.livenessProblems(testScheduler.currentTime, 60.seconds, 3.minutes))
    }

    @Test
    fun `a probe hanging past its limit fails liveness`() = runTest {
        val registry = TargetRegistry(listOf(a))
        val scheduler = ProbeScheduler(
            registry,
            { kind, target -> kotlinx.coroutines.delay(1.hours); outcome(kind, target) },
            {},
            this,
            clock = { testScheduler.currentTime },
        )
        val job = scheduler.start(ProbeKind.LOGIN, 10.minutes)
        advanceTimeBy(1.seconds)
        assertEquals(emptyList<String>(), scheduler.livenessProblems(testScheduler.currentTime, 60.seconds, 3.minutes))
        assertEquals(
            listOf("login dev/a running for 181s (limit 180s)"),
            scheduler.livenessProblems(181_000, 60.seconds, 3.minutes),
        )
        job!!.cancel()
        coroutineContext[kotlinx.coroutines.Job]!!.children.forEach { it.cancel() }
    }

    @Test
    fun `periodic jobs are watched by name`() = runTest {
        val scheduler = ProbeScheduler(TargetRegistry(emptyList()), { kind, target -> outcome(kind, target) }, {}, this, clock = { testScheduler.currentTime })
        val job = scheduler.startPeriodic("catalog refresh dev", 1.hours) {}
        advanceTimeBy(1.seconds)
        assertEquals(emptyList<String>(), scheduler.livenessProblems(testScheduler.currentTime, 60.seconds, 3.minutes))
        assertEquals(listOf("catalog refresh dev overdue by 61s"), scheduler.livenessProblems(3_600_000 + 61_000, 60.seconds, 3.minutes))
        job!!.cancel()
    }
}
