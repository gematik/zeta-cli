package de.gematik.zeta.stress.report

import de.gematik.zeta.stress.db.ResultStore
import de.gematik.zeta.stress.runner.ResultRow
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

data class RunMeta(
    val resource: String,
    val scenario: String,
    val cohort: Int,
    val concurrency: Int,
    val startedAt: LocalDateTime,
    val wallMs: Long,
    val phases: List<PhaseMarker> = emptyList(),
)

/** Writes a timestamped run report — `reports/<ts>/results.csv` + `report.html` — and returns the folder. */
object ReportWriter {
    private val FOLDER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun write(meta: RunMeta, rows: List<ResultRow>): Path {
        val dir = Path.of("reports", meta.startedAt.format(FOLDER))
        Files.createDirectories(dir)
        ResultStore.exportCsv(rows, dir.resolve("results.csv"))
        Files.writeString(dir.resolve("report.html"), buildHtml(meta, rows))
        return dir
    }

    private fun pct(sorted: List<Long>, p: Int): Long {
        if (sorted.isEmpty()) return 0
        return sorted[((p / 100.0) * (sorted.size - 1)).toInt()]
    }

    private fun buildHtml(meta: RunMeta, rows: List<ResultRow>): String {
        val ok = rows.filter { it.ok }
        val failCount = rows.size - ok.size
        val wallSec = (meta.wallMs / 1000.0).coerceAtLeast(0.001)
        val thrpt = rows.size / wallSec
        val lat = ok.map { it.latencyMs }.sorted()

        // trailing time-bucketed series (cap at ~300 points)
        val t0 = rows.minOfOrNull { it.atMs } ?: 0L
        val bucketSec = maxOf(1, ceil(wallSec / 300.0).toInt())
        val byBucket = sortedMapOf<Int, MutableList<ResultRow>>()
        for (r in rows) {
            val b = (((r.atMs - t0) / 1000) / bucketSec).toInt()
            byBucket.getOrPut(b) { mutableListOf() }.add(r)
        }
        val thr = mutableListOf<Pair<Double, Double>>()
        val p50s = mutableListOf<Pair<Double, Double>>()
        val p95s = mutableListOf<Pair<Double, Double>>()
        val p99s = mutableListOf<Pair<Double, Double>>()
        for ((b, rs) in byBucket) {
            val tSec = (b * bucketSec).toDouble()
            thr += tSec to rs.size.toDouble() / bucketSec
            val ls = rs.filter { it.ok }.map { it.latencyMs }.sorted()
            p50s += tSec to pct(ls, 50).toDouble()
            p95s += tSec to pct(ls, 95).toDouble()
            p99s += tSec to pct(ls, 99).toDouble()
        }
        val xMax = wallSec
        val thrMax = (thr.maxOfOrNull { it.second } ?: 1.0) * 1.1
        val latMax = (p99s.maxOfOrNull { it.second } ?: 1.0) * 1.1

        val throughputSvg = lineChart(
            listOf(Series("req/s", "#14b8a6", thr)), xMax, thrMax, "elapsed (s)", "req/s", meta.phases,
        )
        val latencySvg = lineChart(
            listOf(
                Series("p50", "#38bdf8", p50s),
                Series("p95", "#f59e0b", p95s),
                Series("p99", "#f43f5e", p99s),
            ),
            xMax, latMax, "elapsed (s)", "latency (ms)", meta.phases,
        )
        val histSvg = histogram(latencyBins(lat), "latency (ms)", "count", "#6366f1")

        val errorRows = rows.filterNot { it.ok }
            .groupingBy { it.error ?: "unknown" }.eachCount().entries
            .sortedByDescending { it.value }
            .joinToString("") { (err, n) -> "<tr><td class=\"num\">$n</td><td>${escHtml(err)}</td></tr>" }

        return page(meta, rows.size, ok.size, failCount, thrpt, lat, throughputSvg, latencySvg, histSvg, errorRows)
    }

    private fun latencyBins(sorted: List<Long>): List<Bin> {
        if (sorted.isEmpty()) return emptyList()
        val hi = maxOf(pct(sorted, 99) * 1.3, sorted.last().toDouble() * 0.2, 10.0)
        val n = 30
        val step = hi / n
        val counts = IntArray(n)
        for (v in sorted) {
            val i = (v / step).toInt().coerceIn(0, n - 1)
            counts[i]++
        }
        return (0 until n).map { Bin(it * step, (it + 1) * step, counts[it]) }
    }

    private fun statCard(label: String, value: String, tone: String = ""): String =
        """<div class="card $tone"><div class="k">${escHtml(label)}</div><div class="v">${escHtml(value)}</div></div>"""

    private fun page(
        meta: RunMeta,
        total: Int,
        ok: Int,
        fail: Int,
        thrpt: Double,
        lat: List<Long>,
        throughputSvg: String,
        latencySvg: String,
        histSvg: String,
        errorRows: String,
    ): String {
        val failTone = if (fail > 0) "bad" else "good"
        val cards = buildString {
            append(statCard("attempts", "$total"))
            append(statCard("succeeded", "$ok", "good"))
            append(statCard("failed", "$fail", failTone))
            append(statCard("throughput", "%.1f req/s".format(thrpt)))
            append(statCard("p50", "${pct(lat, 50)} ms"))
            append(statCard("p95", "${pct(lat, 95)} ms"))
            append(statCard("p99", "${pct(lat, 99)} ms"))
        }
        val legend = """
            <span class="lg"><i style="background:#38bdf8"></i>p50</span>
            <span class="lg"><i style="background:#f59e0b"></i>p95</span>
            <span class="lg"><i style="background:#f43f5e"></i>p99</span>
        """.trimIndent()
        val errorSection = if (fail > 0) {
            """<section class="block"><h2>Failures</h2><table class="tbl"><thead><tr><th>count</th><th>error</th></tr></thead><tbody>$errorRows</tbody></table></section>"""
        } else {
            ""
        }
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>zeta-stress · ${escHtml(meta.scenario)} · ${meta.startedAt.format(FOLDER)}</title>
<style>
:root{--bg:#ffffff;--panel:#f7f8fa;--border:#e4e7ec;--text:#12161c;--muted:#616b7a;--accent:#0d9488;--grid:#e8ebef;--axis:#98a2b3;--marker:#c026d3;--good:#0e9f6e;--bad:#e02424;}
@media (prefers-color-scheme:dark){:root{--bg:#0e1217;--panel:#161b22;--border:#232a33;--text:#e7ebf0;--muted:#8b95a4;--accent:#2dd4bf;--grid:#20272f;--axis:#5a6472;--marker:#e879f9;--good:#34d399;--bad:#f87171;}}
*{box-sizing:border-box}
body{margin:0;padding:32px;background:var(--bg);color:var(--text);font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif}
.wrap{max-width:960px;margin:0 auto}
h1{font-size:20px;margin:0 0 4px;letter-spacing:-.01em}
.sub{color:var(--muted);font-size:13px;margin-bottom:24px}
.sub b{color:var(--text);font-weight:600}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(120px,1fr));gap:12px;margin-bottom:28px}
.card{background:var(--panel);border:1px solid var(--border);border-radius:10px;padding:12px 14px}
.card .k{color:var(--muted);font-size:11px;text-transform:uppercase;letter-spacing:.04em}
.card .v{font-size:20px;font-weight:650;margin-top:3px;font-variant-numeric:tabular-nums}
.card.good .v{color:var(--good)}.card.bad .v{color:var(--bad)}
.block{background:var(--panel);border:1px solid var(--border);border-radius:12px;padding:18px 20px;margin-bottom:22px}
.block h2{font-size:14px;margin:0 0 10px;font-weight:600}
.chart{width:100%;height:auto;display:block}
.chart .grid{stroke:var(--grid);stroke-width:1}
.chart .axis{fill:var(--axis);font-size:11px;font-family:ui-monospace,SFMono-Regular,Menlo,monospace}
.chart .axis-title{fill:var(--muted);font-size:12px}
.chart .marker{stroke:var(--marker);stroke-width:1;stroke-dasharray:3 3;opacity:.55}
.chart .marker-label{fill:var(--marker);font-size:10px;font-family:ui-monospace,monospace}
.legend{display:flex;gap:16px;margin-top:8px;color:var(--muted);font-size:12px}
.lg{display:inline-flex;align-items:center;gap:6px}.lg i{width:11px;height:11px;border-radius:2px;display:inline-block}
.tbl{width:100%;border-collapse:collapse;font-size:13px}
.tbl th{text-align:left;color:var(--muted);font-weight:600;border-bottom:1px solid var(--border);padding:6px 8px}
.tbl td{border-bottom:1px solid var(--border);padding:6px 8px;vertical-align:top}
.tbl td.num{font-variant-numeric:tabular-nums;color:var(--bad);font-weight:600;width:64px}
.foot{color:var(--muted);font-size:12px;margin-top:24px}
code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;background:var(--panel);border:1px solid var(--border);border-radius:5px;padding:1px 5px}
</style></head>
<body><div class="wrap">
<h1>zeta-stress report</h1>
<div class="sub"><b>${escHtml(meta.scenario)}</b> → <b>${escHtml(meta.resource)}</b> · cohort <b>${meta.cohort}</b> · concurrency <b>${meta.concurrency}</b> · ${meta.startedAt.format(HUMAN)} · wall ${"%.1f".format(meta.wallMs / 1000.0)}s</div>
<div class="cards">$cards</div>
<section class="block"><h2>Throughput over time</h2>$throughputSvg</section>
<section class="block"><h2>Latency percentiles over time</h2>$latencySvg<div class="legend">$legend</div></section>
<section class="block"><h2>Latency distribution (successful attempts)</h2>$histSvg</section>
$errorSection
<div class="foot">Raw per-attempt data in <code>results.csv</code> alongside this file.</div>
</div></body></html>"""
    }

    private fun escHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
