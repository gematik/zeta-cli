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
package de.gematik.zeta.stress.db

import de.gematik.zeta.stress.runner.ResultRow
import java.nio.file.Path
import kotlin.io.path.bufferedWriter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

@Serializable
private data class ResultJson(val at_ms: Long, val op: String, val latency_ms: Long, val ok: Boolean, val error: String?)

/** Persists per-attempt results to the `result` table and exports them to CSV / JSON. */
class ResultStore(private val db: Db) {

    fun insertAll(rows: List<ResultRow>) {
        if (rows.isEmpty()) return
        db.transaction { c ->
            c.prepareStatement(
                "INSERT INTO result(ts, client_ref, op, latency_ms, outcome, error) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                for (r in rows) {
                    ps.setLong(1, r.atMs)
                    ps.setString(2, r.clientRef)
                    ps.setString(3, r.op)
                    ps.setLong(4, r.latencyMs)
                    ps.setString(5, if (r.ok) "ok" else "fail")
                    ps.setString(6, r.error)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    companion object {
        private val json = Json { prettyPrint = true; encodeDefaults = true }

        /**
         * [t0] is the run's first-attempt timestamp; each row's `elapsed_ms` is measured from it and
         * [phaseAt] maps that elapsed time (in seconds) to the waveform phase name (empty for ramp /
         * gaps). These extra columns let the CSV be plotted per-phase without re-deriving the timeline.
         */
        fun exportCsv(rows: List<ResultRow>, path: Path, t0: Long, scenario: String, phaseAt: (Double) -> String?) {
            path.bufferedWriter().use { w ->
                w.appendLine("at_ms,elapsed_ms,scenario,phase,client_ref,telematik_id,op,http_status,latency_ms,ok,error")
                for (r in rows) {
                    val elapsed = r.atMs - t0
                    w.appendLine(
                        "${r.atMs},$elapsed,${csv(scenario)},${csv(phaseAt(elapsed / 1000.0))},${csv(r.clientRef)},${csv(r.telematikId)}," +
                            "${r.op},${r.httpStatus ?: ""},${r.latencyMs},${r.ok},${csv(r.error)}",
                    )
                }
            }
        }

        fun exportJson(rows: List<ResultRow>, path: Path) {
            val payload = rows.map { ResultJson(it.atMs, it.op, it.latencyMs, it.ok, it.error) }
            path.bufferedWriter().use { it.write(json.encodeToString(payload)) }
        }

        private fun csv(value: String?): String {
            val v = value ?: return ""
            return if (v.any { it == ',' || it == '"' || it == '\n' }) "\"${v.replace("\"", "\"\"")}\"" else v
        }
    }
}
