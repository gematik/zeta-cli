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
 */
internal class ProbeScheduler(
    private val targets: TargetRegistry,
    private val runner: ProbeRunner,
    private val onOutcome: (ProbeOutcome) -> Unit,
    private val scope: CoroutineScope,
) : Closeable {

    fun start(kind: ProbeKind, interval: Duration): Job? {
        if (interval <= Duration.ZERO) return null
        return scope.launch {
            var lastKey: String? = null
            while (isActive) {
                val list = targets.current
                val target = nextTarget(list, lastKey)
                if (target == null) {
                    delay(minOf(interval, EMPTY_LIST_RETRY))
                    continue
                }
                lastKey = target.key
                scope.launch {
                    try {
                        onOutcome(runner.run(kind, target))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        log.error(e) { "probe ${kind.label} ${target.resource} crashed" }
                    }
                }
                delay(tickInterval(interval, list.size))
            }
        }
    }

    /** A plain periodic job on the scheduler's scope (catalog refresh); the first run is after [interval]. */
    fun startPeriodic(interval: Duration, block: suspend () -> Unit): Job? {
        if (interval <= Duration.ZERO) return null
        return scope.launch {
            while (isActive) {
                delay(interval)
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.warn(e) { "periodic task failed" }
                }
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
        session.mutex.withLock { runCatching { session.close() }.onFailure { log.debug(it) { "closing session ${target.resource}" } } }
    }

    override fun close() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
    }
}
