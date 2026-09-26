package de.gematik.zeta.cli.probe

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val log = KotlinLogging.logger {}

private val EMPTY_LIST_RETRY = 30.seconds

internal fun interface ProbeRunner {
    suspend fun run(kind: ProbeKind, target: ProbeTarget): ProbeOutcome
}

/**
 * One round-robin loop per probe kind: every `interval / n` it launches the next target's probe and
 * moves on, so a slow target never holds up the others and the load on the auth servers is spread
 * evenly across the interval instead of arriving as a burst. The target list is re-read on every tick.
 * A target held off by [backoff] still consumes its tick, so the cadence of the others is unchanged.
 */
internal class ProbeScheduler(
    private val targets: TargetRegistry,
    private val runner: ProbeRunner,
    private val onOutcome: (ProbeOutcome) -> Unit,
    private val scope: CoroutineScope,
    private val backoff: BackoffPolicy? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {
    /** A loop's job and when it promised to wake up next; the liveness check compares that with the clock. */
    private class LoopState(val name: String) {
        @Volatile var job: Job? = null
        @Volatile var nextDueMs: Long = 0
    }

    private val loops = ConcurrentHashMap<String, LoopState>()
    private val inFlight = ConcurrentHashMap<String, Long>()

    fun start(kind: ProbeKind, interval: Duration): Job? {
        if (interval <= Duration.ZERO) return null
        val state = register("${kind.label} loop")
        return scope.launch {
            var lastKey: String? = null
            while (isActive) {
                val list = targets.current
                val target = nextTarget(list, lastKey)
                if (target == null) {
                    sleep(state, minOf(interval, EMPTY_LIST_RETRY))
                    continue
                }
                lastKey = target.key
                if (backoff != null && !backoff.shouldRun(kind, target.key, clock())) {
                    log.debug { "skip ${kind.label} ${target.envLabel}/${target.service}: backing off" }
                } else {
                    launchProbe(kind, target)
                }
                sleep(state, tickInterval(interval, list.size))
            }
        }.also { state.job = it }
    }

    private fun launchProbe(kind: ProbeKind, target: ProbeTarget) {
        val name = "${kind.label} ${target.envLabel}/${target.service}"
        val runId = "$name#${System.nanoTime()}"
        scope.launch {
            inFlight[runId] = clock()
            try {
                val outcome = runner.run(kind, target)
                backoff?.record(outcome, clock())
                onOutcome(outcome)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                backoff?.record(kind, target, ok = false, nowMs = clock())
                log.error(e) { "probe $name crashed" }
            } finally {
                inFlight.remove(runId)
            }
        }
    }

    /** A named periodic job on the scheduler's scope (catalog refresh); the first run is after [interval]. */
    fun startPeriodic(name: String, interval: Duration, block: suspend () -> Unit): Job? {
        if (interval <= Duration.ZERO) return null
        val state = register(name)
        return scope.launch {
            while (isActive) {
                sleep(state, interval)
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.warn(e) { "$name failed" }
                }
            }
        }.also { state.job = it }
    }

    private fun register(name: String): LoopState =
        LoopState(name).also { it.nextDueMs = clock(); loops[name] = it }

    private suspend fun sleep(state: LoopState, d: Duration) {
        state.nextDueMs = clock() + d.inWholeMilliseconds
        delay(d)
    }

    /**
     * What is wrong with the scheduler right now, empty when healthy: a loop whose job ended, a loop that
     * has not woken up [grace] after it was due (a starved or wedged dispatcher), and a probe running
     * longer than [maxProbeRun] (a blocking call that ignores the probe timeout's cancellation).
     */
    fun livenessProblems(nowMs: Long, grace: Duration, maxProbeRun: Duration): List<String> = buildList {
        loops.values.sortedBy { it.name }.forEach { loop ->
            val job = loop.job
            if (job != null && !job.isActive) {
                add("${loop.name} stopped")
            } else {
                val overdueMs = nowMs - loop.nextDueMs - grace.inWholeMilliseconds
                if (overdueMs > 0) add("${loop.name} overdue by ${(overdueMs + grace.inWholeMilliseconds) / 1000}s")
            }
        }
        inFlight.entries.sortedBy { it.value }.forEach { (runId, startedMs) ->
            val runningMs = nowMs - startedMs
            if (runningMs > maxProbeRun.inWholeMilliseconds) {
                add("${runId.substringBefore('#')} running for ${runningMs / 1000}s (limit ${maxProbeRun.inWholeSeconds}s)")
            }
        }
    }

    override fun close() = scope.cancel()
}

/** Lazily built, process-lifetime [TargetSession]s, one per target. */
internal class SessionRegistry(private val build: (ProbeTarget) -> TargetSession) : Closeable {
    private val sessions = ConcurrentHashMap<String, TargetSession>()
    private val buildLock = Mutex()

    suspend fun sessionFor(target: ProbeTarget): TargetSession =
        sessions[target.key] ?: buildLock.withLock { sessions.getOrPut(target.key) { build(target) } }

    suspend fun evict(target: ProbeTarget) {
        val session = sessions.remove(target.key) ?: return
        session.mutex.withLock { runCatching { session.close() }.onFailure { log.debug(it) { "closing session ${target.envLabel}/${target.service}" } } }
    }

    override fun close() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
    }
}
