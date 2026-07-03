package de.gematik.zeta.stress.runner

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

private val log = KotlinLogging.logger {}

/** Target admission rate (requests/min) as a function of elapsed time. */
fun interface RateSchedule {
    fun ratePerMinAt(elapsedMs: Long): Int
}

object Rates {
    fun constant(perMin: Int) = RateSchedule { perMin }

    /** Start at [startPerMin], add [stepPerMin] every [stepEveryMs], capped at [maxPerMin]. */
    fun ramp(startPerMin: Int, stepPerMin: Int, stepEveryMs: Long, maxPerMin: Int) = RateSchedule { elapsed ->
        (startPerMin + stepPerMin * (elapsed / stepEveryMs)).toInt().coerceIn(1, maxPerMin)
    }
}

data class DriverResult(val stoppedEarly: Boolean, val peakHealthyPerMin: Int)

/**
 * Drives [action] continuously over [items] (round-robin) for up to [durationMs], pacing to
 * [schedule]'s current rate. Concurrency is capped at [concurrency]; when the server can't keep
 * up, `gate.acquire()` backpressures the pacing loop, so the achieved rate naturally plateaus.
 *
 * Every second a ticker computes a trailing-[windowMs] [Snapshot], reports it via [onTick], and —
 * for ramp — consults [stopWhen] to detect the breaking point. [peakHealthyPerMin] tracks the
 * highest achieved throughput observed while still healthy.
 */
suspend fun <T> runContinuous(
    items: List<T>,
    concurrency: Int,
    schedule: RateSchedule,
    durationMs: Long,
    clockMs: () -> Long,
    reporter: Reporter,
    windowMs: Long = 3000,
    onTick: (elapsedMs: Long, targetPerMin: Int, window: Snapshot) -> Unit = { _, _, _ -> },
    stopWhen: ((Snapshot) -> Boolean)? = null,
    action: suspend (T) -> Unit,
): DriverResult = coroutineScope {
    require(items.isNotEmpty()) { "no items to drive" }
    val gate = Semaphore(concurrency)
    val stop = AtomicBoolean(false)
    val start = clockMs()
    var peakHealthy = 0

    val ticker = launch {
        while (isActive && !stop.get()) {
            delay(1000)
            val elapsed = clockMs() - start
            val target = schedule.ratePerMinAt(elapsed)
            val w = reporter.window(windowMs)
            onTick(elapsed, target, w)
            if (stopWhen != null && w.count >= 5) {
                if (stopWhen(w)) {
                    stop.set(true)
                } else {
                    peakHealthy = maxOf(peakHealthy, (w.throughputPerSec * 60).toInt())
                }
            }
        }
    }

    var idx = 0
    var nextSlot = start.toDouble()
    val jobs = mutableListOf<Job>()
    while (!stop.get() && (clockMs() - start) < durationMs) {
        val elapsed = clockMs() - start
        val interval = 60_000.0 / schedule.ratePerMinAt(elapsed).coerceAtLeast(1)
        val now = clockMs()
        if (now < nextSlot) delay((nextSlot - now).toLong())
        // Anchor the next slot to now (not nextSlot+interval) so a backpressure stall doesn't
        // bank a burst of catch-up work afterwards.
        nextSlot = maxOf(nextSlot + interval, clockMs().toDouble())

        val item = items[idx++ % items.size]
        gate.acquire()
        jobs += launch {
            try {
                action(item)
            } finally {
                gate.release()
            }
        }
        if (jobs.size > concurrency * 4) jobs.removeAll { it.isCompleted }
    }

    log.debug { "Driver draining ${jobs.count { !it.isCompleted }} in-flight" }
    jobs.forEach { it.join() }
    ticker.cancel()
    DriverResult(stoppedEarly = stop.get(), peakHealthyPerMin = peakHealthy)
}
