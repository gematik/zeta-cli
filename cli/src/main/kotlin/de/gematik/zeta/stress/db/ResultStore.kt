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
                "INSERT INTO result(ts, client_ref, op, latency_ms, outcome, error) VALUES (?, '', ?, ?, ?, ?)",
            ).use { ps ->
                for (r in rows) {
                    ps.setLong(1, r.atMs)
                    ps.setString(2, r.op)
                    ps.setLong(3, r.latencyMs)
                    ps.setString(4, if (r.ok) "ok" else "fail")
                    ps.setString(5, r.error)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    companion object {
        private val json = Json { prettyPrint = true; encodeDefaults = true }

        fun exportCsv(rows: List<ResultRow>, path: Path) {
            path.bufferedWriter().use { w ->
                w.appendLine("at_ms,op,latency_ms,ok,error")
                for (r in rows) {
                    w.appendLine("${r.atMs},${r.op},${r.latencyMs},${r.ok},${csv(r.error)}")
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
