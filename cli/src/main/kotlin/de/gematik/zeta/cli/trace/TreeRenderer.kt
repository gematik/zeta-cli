package de.gematik.zeta.cli.trace

/**
 * Render a span tree as a plain-text ASCII tree. One line per span:
 *
 *   cli.run (1.84s) [argv=connector get cards]
 *   ├─ sdk.session (1.81s)
 *   │  ├─ sdk.init (32ms)
 *   │  └─ connector.getAllCards (320ms)
 *   └─ ...
 *
 * Failed spans get a trailing `! <error>` marker. No colour — Logback's STDERR appender
 * already applies its own pattern and the multi-line tree shouldn't be split into
 * separately highlighted segments.
 */
internal fun renderSpanTree(root: Span): String = buildString {
    appendSpan(root, prefix = "", isLast = true, isRoot = true)
}.trimEnd()

private fun StringBuilder.appendSpan(span: Span, prefix: String, isLast: Boolean, isRoot: Boolean) {
    val connector = when {
        isRoot -> ""
        isLast -> "└─ "
        else -> "├─ "
    }
    append(prefix).append(connector).append(span.name)
    append(" (").append(formatDuration(span.durationMs)).append(')')
    if (span.attrs.isNotEmpty()) {
        append(' ').append(span.attrs.entries.joinToString(" ") { (k, v) -> "$k=${formatAttr(v)}" })
    }
    if (!span.ok) {
        append("  ! ").append(span.error ?: "failed")
    }
    append('\n')
    val childPrefix = when {
        isRoot -> ""
        isLast -> "$prefix   "
        else -> "$prefix│  "
    }
    span.children.forEachIndexed { idx, child ->
        appendSpan(child, childPrefix, isLast = idx == span.children.lastIndex, isRoot = false)
    }
}

private fun formatDuration(ms: Double): String = when {
    ms < 0 -> "?"
    ms < 1.0 -> "%.2fms".format(ms)
    ms < 1000.0 -> "%.0fms".format(ms)
    else -> "%.2fs".format(ms / 1000.0)
}

private fun formatAttr(value: Any?): String = when (value) {
    null -> "null"
    is String -> if (value.any { it.isWhitespace() }) "\"$value\"" else value
    is Collection<*> -> value.joinToString(",", "[", "]") { formatAttr(it) }
    else -> value.toString()
}
