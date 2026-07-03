package de.gematik.zeta.stress.runner

/** Contract progress callbacks bind to; either the live panel or the plain printer. */
interface Progress {
    /** Rate-driven work (soak/ramp): current vs target admission rate. */
    fun tick(elapsedMs: Long, targetPerMin: Int, w: Snapshot)

    /** Finite work (preflight): [done] of [total] units complete. */
    fun tickCount(elapsedMs: Long, done: Int, total: Int, w: Snapshot)

    fun close()
}

/** One line per second on stderr — used when output isn't a TTY, so pipes/CI stay readable. */
class PlainProgress(private val out: Appendable = System.err) : Progress {
    override fun tick(elapsedMs: Long, targetPerMin: Int, w: Snapshot) {
        emit(
            "[t=%4ds] target %5d/min  did %5.0f/min  ok %d fail %d  p50 %dms p95 %dms p99 %dms".format(
                elapsedMs / 1000, targetPerMin, w.throughputPerSec * 60, w.ok, w.fail, w.p50, w.p95, w.p99,
            ),
        )
    }

    override fun tickCount(elapsedMs: Long, done: Int, total: Int, w: Snapshot) {
        emit(
            "[t=%4ds] %d/%d done  ok %d fail %d  %5.0f/min  p50 %dms p95 %dms p99 %dms".format(
                elapsedMs / 1000, done, total, w.ok, w.fail, w.throughputPerSec * 60, w.p50, w.p95, w.p99,
            ),
        )
    }

    private fun emit(line: String) {
        out.append(line).append('\n')
        (out as? java.io.Flushable)?.flush()
    }

    override fun close() {}
}

/**
 * A k9s-style live panel that redraws in place on stderr each tick: a bordered box with the
 * current admission rate, achieved throughput (with a bar + rolling sparkline), latency
 * percentiles (colour-coded), and success/failure counts. Only used on an interactive terminal;
 * piped/redirected runs get [PlainProgress] instead so logs stay clean.
 */
class LiveView(
    private val title: String,
    private val scenario: String,
    private val target: String,
    private val colorize: Boolean,
    private val out: Appendable = System.err,
) : Progress {

    private val inner = 62
    private val history = ArrayDeque<Double>()
    private val sparkCells = 44
    private var started = false
    private val lines = 7

    override fun tick(elapsedMs: Long, targetPerMin: Int, w: Snapshot) {
        val achieved = w.throughputPerSec * 60
        val frac = if (targetPerMin > 0) achieved / targetPerMin else 0.0
        val headline = label("RATE") + " " + bar(frac, 16, CYAN) + "  " +
            color(pad("${achieved.toInt()}/min", 10), BOLD) +
            color("/ ${targetPerMin}/min target", DIM)
        render(elapsedMs, headline, w)
    }

    override fun tickCount(elapsedMs: Long, done: Int, total: Int, w: Snapshot) {
        val frac = if (total > 0) done.toDouble() / total else 0.0
        val headline = label("DONE") + " " + bar(frac, 16, CYAN) + "  " +
            color(pad("$done / $total", 12), BOLD) +
            color("(${(frac * 100).toInt()}%)", DIM)
        render(elapsedMs, headline, w)
    }

    private fun render(elapsedMs: Long, headline: String, w: Snapshot) {
        history.addLast(w.throughputPerSec)
        while (history.size > sparkCells) history.removeFirst()

        val sb = StringBuilder()
        if (started) sb.append("$ESC[${lines}A") else started = true

        fun emit(s: String) = sb.append("$ESC[2K").append(s).append('\n')

        emit(topBorder())
        emit(box(field("target", truncate(target, 34)) + gap() + field("elapsed", clock(elapsedMs))))
        emit(box(""))
        emit(box(headline))
        emit(
            box(
                label("REQ ") + " " +
                    color("ok ", DIM) + color(pad(w.ok.toString(), 8), GREEN) +
                    color("fail ", DIM) + color(pad(w.fail.toString(), 7), if (w.fail > 0) RED else DIM) +
                    color("thrpt ", DIM) + color("${"%.1f".format(w.throughputPerSec)}/s", BOLD),
            ),
        )
        emit(
            box(
                label("LAT ") + " " +
                    lat("p50", w.p50) + "   " + lat("p95", w.p95) + "   " + lat("p99", w.p99),
            ),
        )
        emit(bottomBorder(sparkline()))

        out.append(sb)
        (out as? java.io.Flushable)?.flush()
    }

    override fun close() {
        (out as? java.io.Flushable)?.flush()
    }

    private fun sparkline(): String {
        if (history.isEmpty()) return ""
        val max = history.max().coerceAtLeast(1.0)
        val blocks = "▁▂▃▄▅▆▇█"
        val s = history.joinToString("") { v ->
            val idx = ((v / max) * (blocks.length - 1)).toInt().coerceIn(0, blocks.length - 1)
            blocks[idx].toString()
        }
        return color(s, CYAN) + color(" thrpt", DIM)
    }

    private fun lat(name: String, ms: Long): String {
        val c = when {
            ms >= 3000 -> RED
            ms >= 1000 -> YELLOW
            else -> GREEN
        }
        return color("$name ", DIM) + color("${ms}ms", c)
    }

    private fun field(k: String, v: String) = color("$k ", DIM) + color(v, WHITE)
    private fun label(s: String) = color(s, BOLD)
    private fun gap() = "    "

    // Box drawing — pads to the visible inner width, ignoring ANSI escapes.
    private fun box(content: String): String {
        val vis = visibleLen(content)
        val padded = content + " ".repeat((inner - vis).coerceAtLeast(0))
        return color("│ ", BORDER) + padded + color(" │", BORDER)
    }

    private fun topBorder(): String {
        val head = " $title · $scenario "
        val dashes = (inner + 2 - head.length).coerceAtLeast(2)
        return color("┌", BORDER) + color(head, CYAN_B) +
            color("─".repeat(dashes), BORDER) + color("┐", BORDER)
    }

    private fun bottomBorder(spark: String): String {
        val vis = visibleLen(spark)
        val dashes = (inner - 1 - vis).coerceAtLeast(2)
        return color("└─ ", BORDER) + spark + " " + color("─".repeat(dashes), BORDER) + color("┘", BORDER)
    }

    private fun bar(frac: Double, cells: Int, fg: String): String {
        val f = frac.coerceIn(0.0, 1.0)
        val filled = (f * cells).toInt()
        return color("█".repeat(filled), fg) + color("░".repeat(cells - filled), DIM)
    }

    private fun pad(s: String, w: Int) = s.padEnd(w)
    private fun truncate(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"

    private fun color(s: String, code: String) = if (colorize && s.isNotEmpty()) "$code$s$RESET" else s

    private fun visibleLen(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            if (s[i] == ESC_CHAR) {
                while (i < s.length && s[i] != 'm') i++
                i++
            } else {
                n++; i++
            }
        }
        return n
    }

    private fun clock(ms: Long): String {
        val total = ms / 1000
        return "%02d:%02d".format(total / 60, total % 60)
    }

    private companion object {
        val ESC_CHAR = Char(27)
        val ESC = ESC_CHAR.toString()
        val RESET = "$ESC[0m"
        val BOLD = "$ESC[1m"
        val DIM = "$ESC[90m"
        val WHITE = "$ESC[97m"
        val GREEN = "$ESC[32m"
        val YELLOW = "$ESC[33m"
        val RED = "$ESC[31m"
        val CYAN = "$ESC[36m"
        val CYAN_B = "$ESC[1m$ESC[96m"
        val BORDER = "$ESC[38;5;30m"
    }
}
