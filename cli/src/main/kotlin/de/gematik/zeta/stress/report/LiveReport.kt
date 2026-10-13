/*
 * Copyright (Change Date see Readme), gematik GmbH
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the
 * European Commission – subsequent versions of the EUPL (the "Licence").
 * You may not use this work except in compliance with the Licence.
 *
 * You find a copy of the Licence in the "Licence" file or at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.
 * In case of changes by gematik GmbH find details in the "Readme" file.
 *
 * See the Licence for the specific language governing permissions and limitations under the Licence.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 */
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
