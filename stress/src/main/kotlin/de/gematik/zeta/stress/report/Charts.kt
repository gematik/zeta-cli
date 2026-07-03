package de.gematik.zeta.stress.report

import java.util.Locale

/** A named line series sharing the chart's x-axis. Points are (x, y) in data coordinates. */
data class Series(val name: String, val color: String, val points: List<Pair<Double, Double>>)

/** A phase boundary drawn as a dashed vertical rule on time charts. */
data class PhaseMarker(val atSec: Double, val name: String)

/** One histogram bucket. */
data class Bin(val lo: Double, val hi: Double, val count: Int)

private const val W = 860
private const val H = 320
private const val PAD_L = 58
private const val PAD_R = 18
private const val PAD_T = 18
private const val PAD_B = 46

// SVG coordinates and axis labels MUST use '.' as the decimal separator regardless of the JVM's
// default locale — a comma (e.g. de_DE) inside points="x,y" corrupts every coordinate.
private fun num(v: Double): String {
    val fmt = when {
        v >= 100 -> "%.0f"
        v >= 1 -> "%.1f"
        else -> "%.2f"
    }
    return String.format(Locale.ROOT, fmt, v)
}

private fun esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun d1(v: Double): String = String.format(Locale.ROOT, "%.1f", v)

/** A multi-series line chart with gridlines, axis ticks, and optional phase markers. */
fun lineChart(
    series: List<Series>,
    xMax: Double,
    yMax: Double,
    xLabel: String,
    yLabel: String,
    markers: List<PhaseMarker> = emptyList(),
): String {
    val pw = W - PAD_L - PAD_R
    val ph = H - PAD_T - PAD_B
    val xm = if (xMax <= 0) 1.0 else xMax
    val ym = if (yMax <= 0) 1.0 else yMax
    fun px(x: Double) = PAD_L + (x / xm) * pw
    fun py(y: Double) = PAD_T + ph - (y / ym) * ph

    val sb = StringBuilder()
    sb.append("""<svg viewBox="0 0 $W $H" width="$W" height="$H" class="chart" preserveAspectRatio="xMidYMid meet" role="img">""")
    for (i in 0..5) {
        val yVal = ym * i / 5
        val yy = py(yVal)
        sb.append("""<line class="grid" x1="$PAD_L" y1="${d1(yy)}" x2="${PAD_L + pw}" y2="${d1(yy)}"/>""")
        sb.append("""<text class="axis" x="${PAD_L - 8}" y="${d1(yy + 3)}" text-anchor="end">${num(yVal)}</text>""")
    }
    for (i in 0..6) {
        val xVal = xm * i / 6
        val xx = px(xVal)
        sb.append("""<text class="axis" x="${d1(xx)}" y="${PAD_T + ph + 18}" text-anchor="middle">${num(xVal)}</text>""")
    }
    for (m in markers) {
        if (m.atSec < 0 || m.atSec > xm) continue
        val xx = px(m.atSec)
        sb.append("""<line class="marker" x1="${d1(xx)}" y1="$PAD_T" x2="${d1(xx)}" y2="${PAD_T + ph}"/>""")
        sb.append("""<text class="marker-label" x="${d1(xx + 3)}" y="${PAD_T + 11}">${esc(m.name)}</text>""")
    }
    for (s in series) {
        if (s.points.isEmpty()) continue
        val pts = s.points.joinToString(" ") { "${d1(px(it.first))},${d1(py(it.second))}" }
        sb.append("""<polyline fill="none" stroke="${s.color}" stroke-width="2" stroke-linejoin="round" points="$pts"/>""")
    }
    sb.append("""<text class="axis-title" x="${PAD_L + pw / 2}" y="${H - 6}" text-anchor="middle">${esc(xLabel)}</text>""")
    sb.append("""<text class="axis-title" x="16" y="${PAD_T + ph / 2}" text-anchor="middle" transform="rotate(-90 16 ${PAD_T + ph / 2})">${esc(yLabel)}</text>""")
    sb.append("</svg>")
    return sb.toString()
}

/** A histogram / bar chart. */
fun histogram(bins: List<Bin>, xLabel: String, yLabel: String, color: String): String {
    val pw = W - PAD_L - PAD_R
    val ph = H - PAD_T - PAD_B
    val maxCount = (bins.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)
    val n = bins.size.coerceAtLeast(1)
    val bw = pw.toDouble() / n

    val sb = StringBuilder()
    sb.append("""<svg viewBox="0 0 $W $H" width="$W" height="$H" class="chart" preserveAspectRatio="xMidYMid meet" role="img">""")
    for (i in 0..5) {
        val c = maxCount * i / 5
        val yy = PAD_T + ph - (i / 5.0) * ph
        sb.append("""<line class="grid" x1="$PAD_L" y1="${d1(yy)}" x2="${PAD_L + pw}" y2="${d1(yy)}"/>""")
        sb.append("""<text class="axis" x="${PAD_L - 8}" y="${d1(yy + 3)}" text-anchor="end">$c</text>""")
    }
    bins.forEachIndexed { i, b ->
        val bh = (b.count.toDouble() / maxCount) * ph
        val x = PAD_L + i * bw
        val y = PAD_T + ph - bh
        sb.append("""<rect x="${d1(x + 0.5)}" y="${d1(y)}" width="${d1((bw - 1).coerceAtLeast(0.5))}" height="${d1(bh)}" fill="$color" opacity="0.85"/>""")
    }
    for (i in 0..5) {
        val idx = (n * i / 5).coerceAtMost(n - 1)
        val loMs = bins.getOrNull(idx)?.lo ?: 0.0
        val xx = PAD_L + idx * bw
        sb.append("""<text class="axis" x="${d1(xx)}" y="${PAD_T + ph + 18}" text-anchor="middle">${num(loMs)}</text>""")
    }
    sb.append("""<text class="axis-title" x="${PAD_L + pw / 2}" y="${H - 6}" text-anchor="middle">${esc(xLabel)}</text>""")
    sb.append("""<text class="axis-title" x="16" y="${PAD_T + ph / 2}" text-anchor="middle" transform="rotate(-90 16 ${PAD_T + ph / 2})">${esc(yLabel)}</text>""")
    sb.append("</svg>")
    return sb.toString()
}
