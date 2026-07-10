package de.gematik.zeta.stress.report

import de.gematik.zeta.stress.runner.ResultRow
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * Rewrites the run report into [dir] on a fixed cadence from a daemon thread, so a long stress run
 * has an up-to-date `report.html` you can open while it's still going. [metaNow] is sampled each
 * tick (its `wallMs` advances) and [rowsNow] snapshots the reporter — both must be safe to call off
 * the worker threads (the reporter's queue is concurrent). Best-effort: a failed write is logged and
 * the schedule carries on. [close] stops the cadence; do the authoritative final write yourself
 * afterwards so it can't race a tick.
 */
class LiveReport(
    private val dir: Path,
    private val metaNow: () -> RunMeta,
    private val rowsNow: () -> List<ResultRow>,
    private val everySec: Long = 60,
) : AutoCloseable {

    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "stress-live-report").apply { isDaemon = true }
    }

    fun start() {
        exec.scheduleAtFixedRate({
            runCatching { ReportWriter.write(dir, metaNow(), rowsNow()) }
                .onFailure { log.warn(it) { "live report write failed" } }
        }, everySec, everySec, TimeUnit.SECONDS)
    }

    override fun close() {
        exec.shutdownNow()
    }
}
