package de.gematik.zeta.cli.probe

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProbeSchedulerTest {
    private val a = ProbeTarget("https://a/", listOf("s"), "a")
    private val b = ProbeTarget("https://b/", listOf("s"), "b")
    private val c = ProbeTarget("https://c/", listOf("s"), "c")
    private val d = ProbeTarget("https://d/", listOf("s"), "d")

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
}
