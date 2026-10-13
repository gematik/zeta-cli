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
