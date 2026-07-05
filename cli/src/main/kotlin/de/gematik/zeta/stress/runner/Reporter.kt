package de.gematik.zeta.stress.runner

import java.util.concurrent.ConcurrentLinkedQueue

data class Attempt(val op: String, val latencyMs: Long, val ok: Boolean, val error: String?)

/** A recorded attempt with its completion time — for persistence / export. */
data class ResultRow(val atMs: Long, val op: String, val latencyMs: Long, val ok: Boolean, val error: String?)

/** Stats over some set of attempts — the whole run, or a trailing time window. */
data class Snapshot(
    val count: Int,
    val ok: Int,
    val fail: Int,
    val p50: Long,
    val p95: Long,
    val p99: Long,
    val throughputPerSec: Double,
    // Cumulative run totals, independent of the window — for progress display.
    val totalOk: Int = 0,
    val totalFail: Int = 0,
) {
    val failFraction: Double get() = if (count == 0) 0.0 else fail.toDouble() / count
}

/**
 * Collects per-attempt outcomes with completion timestamps so we can report both a final summary
 * and live trailing-window snapshots (for the progress line and the ramp stop-condition).
 * Thread-safe — worker coroutines append concurrently.
 */
class Reporter(private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }) {

    private class Rec(val atMs: Long, val op: String, val latencyMs: Long, val ok: Boolean, val error: String?)

    private val recs = ConcurrentLinkedQueue<Rec>()
    private val okTotal = java.util.concurrent.atomic.AtomicInteger()
    private val failTotal = java.util.concurrent.atomic.AtomicInteger()

    fun record(attempt: Attempt) {
        recs += Rec(clockMs(), attempt.op, attempt.latencyMs, attempt.ok, attempt.error)
        if (attempt.ok) okTotal.incrementAndGet() else failTotal.incrementAndGet()
    }

    val completed: Int get() = recs.size

    /** All recorded attempts with timestamps, for persistence/export. */
    fun rows(): List<ResultRow> = recs.map { ResultRow(it.atMs, it.op, it.latencyMs, it.ok, it.error) }

    /** Snapshot over attempts completed in the last [windowMs]. */
    fun window(windowMs: Long): Snapshot {
        val cutoff = clockMs() - windowMs
        return snapshot(recs.filter { it.atMs >= cutoff }, windowMs)
    }

    fun summary(wallMs: Long): String {
        val all = recs.toList()
        if (all.isEmpty()) return "No attempts recorded."
        val snap = snapshot(all, wallMs)
        val errorHistogram = all.filterNot { it.ok }
            .groupingBy { it.error ?: "unknown" }
            .eachCount()
            .entries
            .sortedByDescending { it.value }

        return buildString {
            appendLine("── Stress summary ──────────────────────────")
            appendLine("attempts     : ${snap.count}")
            appendLine("succeeded    : ${snap.ok}")
            appendLine("failed       : ${snap.fail}")
            appendLine("wall time    : ${"%.1f".format(wallMs / 1000.0)} s")
            appendLine("throughput   : ${"%.1f".format(snap.throughputPerSec)} req/s " +
                "(${"%.0f".format(snap.throughputPerSec * 60)} req/min)")
            if (snap.ok > 0) {
                appendLine("latency (ok) : p50=${snap.p50}ms  p95=${snap.p95}ms  p99=${snap.p99}ms")
            }
            if (errorHistogram.isNotEmpty()) {
                appendLine("failures     :")
                errorHistogram.forEach { (err, n) -> appendLine("  $n×  $err") }
            }
        }
    }

    private fun snapshot(sample: Collection<Rec>, spanMs: Long): Snapshot {
        val okLatencies = sample.filter { it.ok }.map { it.latencyMs }.sorted()
        val throughput = if (spanMs > 0) sample.size * 1000.0 / spanMs else 0.0
        return Snapshot(
            count = sample.size,
            ok = sample.count { it.ok },
            fail = sample.count { !it.ok },
            p50 = pct(okLatencies, 50),
            p95 = pct(okLatencies, 95),
            p99 = pct(okLatencies, 99),
            throughputPerSec = throughput,
            totalOk = okTotal.get(),
            totalFail = failTotal.get(),
        )
    }

    private fun pct(sorted: List<Long>, p: Int): Long {
        if (sorted.isEmpty()) return 0
        val idx = ((p / 100.0) * (sorted.size - 1)).toInt()
        return sorted[idx]
    }
}
