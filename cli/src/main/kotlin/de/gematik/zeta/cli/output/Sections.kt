package de.gematik.zeta.cli.output

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles

// Named TextColors render via the terminal's palette, so a base16_transparent (or any base16)
// terminal theme automatically maps them to its scheme.
private val sectionTitleStyle: TextStyle = TextStyles.bold + TextStyles.underline
private val labelStyle: TextStyle = TextColors.gray
private val stringStyle: TextStyle = TextColors.green
private val numberStyle: TextStyle = TextColors.yellow

internal sealed class FieldItem {
    abstract val label: String

    data class StringValue(override val label: String, val value: String) : FieldItem()
    data class NumberValue(override val label: String, val value: String) : FieldItem()
    data class StringList(override val label: String, val values: List<String>) : FieldItem()
}

internal class SectionData(val title: String, val items: List<FieldItem>)

class FieldsBuilder internal constructor() {
    internal val items = mutableListOf<FieldItem>()

    fun field(label: String, value: String?) {
        if (value.isNullOrBlank()) return
        items += FieldItem.StringValue(label, value)
    }

    fun field(label: String, value: Number?) {
        value ?: return
        items += FieldItem.NumberValue(label, value.toString())
    }

    fun field(label: String, values: List<String>) {
        if (values.isEmpty()) return
        items += FieldItem.StringList(label, values)
    }
}

class SectionsBuilder internal constructor() {
    internal val sections = mutableListOf<SectionData>()

    fun section(title: String, block: FieldsBuilder.() -> Unit) {
        val builder = FieldsBuilder().apply(block)
        if (builder.items.isNotEmpty()) {
            sections += SectionData(title, builder.items)
        }
    }
}

/**
 * Renders one or more sections of `Label: Value` rows.
 *
 * - Section titles → bold + underline (the visual heading).
 * - Labels         → muted/gray (secondary, recedes from values).
 * - String values  → green (base0B).
 * - Number values  → yellow (base09-ish).
 *
 * Multi-value fields wrap continuation lines aligned under the value column.
 * When [colorize] is `false` (e.g. piped/redirected output), all ANSI is suppressed.
 */
fun renderSections(
    colorize: Boolean = true,
    block: SectionsBuilder.() -> Unit,
): String {
    val builder = SectionsBuilder().apply(block)
    return buildString {
        builder.sections.forEachIndexed { idx, section ->
            appendLine(if (colorize) sectionTitleStyle(section.title) else section.title)

            val labelWidth = section.items.maxOf { it.label.length + 1 } // include trailing colon
            section.items.forEach { item -> appendItem(item, labelWidth, colorize) }

            if (idx < builder.sections.lastIndex) appendLine()
        }
    }
}

private fun StringBuilder.appendItem(item: FieldItem, labelWidth: Int, colorize: Boolean) {
    val labelText = "${item.label}:"
    val styledLabel = if (colorize) labelStyle(labelText) else labelText
    val padding = " ".repeat(labelWidth - labelText.length)

    when (item) {
        is FieldItem.StringValue -> {
            val styledValue = if (colorize) stringStyle(item.value) else item.value
            appendLine("  $styledLabel$padding $styledValue")
        }

        is FieldItem.NumberValue -> {
            val styledValue = if (colorize) numberStyle(item.value) else item.value
            appendLine("  $styledLabel$padding $styledValue")
        }

        is FieldItem.StringList -> {
            // Continuation lines start at column = 2 + labelWidth + 1 (margin + label + separator).
            val indent = " ".repeat(2 + labelWidth + 1)
            val first = if (colorize) stringStyle(item.values.first()) else item.values.first()
            appendLine("  $styledLabel$padding $first")
            item.values.drop(1).forEach {
                val styled = if (colorize) stringStyle(it) else it
                appendLine("$indent$styled")
            }
        }
    }
}
