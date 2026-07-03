package de.gematik.zeta.stress.runner

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

data class Attempt(val op: String, val latencyMs: Long, val ok: Boolean, val error: String?)

/**
 * Collects per-attempt outcomes and renders a summary: throughput, latency percentiles, and a
 * failure taxonomy. Thread-safe — worker coroutines append concurrently.
 */
class Reporter {
    private val attempts = ConcurrentLinkedQueue<Attempt>()
    private val done = AtomicInteger()

    fun record(attempt: Attempt) {
        attempts += attempt
        done.incrementAndGet()
    }

    val completed: Int get() = done.get()

    fun summary(wallMs: Long): String {
        val all = attempts.toList()
        if (all.isEmpty()) return "No attempts recorded."
        val ok = all.filter { it.ok }
        val failed = all.filterNot { it.ok }
        val latencies = ok.map { it.latencyMs }.sorted()
        val throughput = if (wallMs > 0) all.size * 1000.0 / wallMs else 0.0

        val errorHistogram = failed
            .groupingBy { it.error ?: "unknown" }
            .eachCount()
            .entries
            .sortedByDescending { it.value }

        return buildString {
            appendLine("── Stress summary ──────────────────────────")
            appendLine("attempts     : ${all.size}")
            appendLine("succeeded    : ${ok.size}")
            appendLine("failed       : ${failed.size}")
            appendLine("wall time    : ${"%.1f".format(wallMs / 1000.0)} s")
            appendLine("throughput   : ${"%.1f".format(throughput)} req/s (${"%.0f".format(throughput * 60)} req/min)")
            if (latencies.isNotEmpty()) {
                appendLine("latency (ok) : p50=${pct(latencies, 50)}ms  p95=${pct(latencies, 95)}ms  " +
                    "p99=${pct(latencies, 99)}ms  max=${latencies.last()}ms")
            }
            if (errorHistogram.isNotEmpty()) {
                appendLine("failures     :")
                errorHistogram.forEach { (err, n) -> appendLine("  $n×  $err") }
            }
        }
    }

    private fun pct(sorted: List<Long>, p: Int): Long {
        if (sorted.isEmpty()) return 0
        val idx = ((p / 100.0) * (sorted.size - 1)).toInt()
        return sorted[idx]
    }
}
