package de.gematik.zeta.cli.output

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// base16-style palette via terminal-named colours. With base16_transparent (or any base16)
// terminal theme, these automatically map to the scheme's hex values.
private val keyStyle: TextStyle = TextColors.blue          // base0D — functions / keys
private val stringStyle: TextStyle = TextColors.green      // base0B — strings
private val numberStyle: TextStyle = TextColors.yellow     // base09/0A — numerics
private val booleanStyle: TextStyle = TextColors.magenta   // base0E — keywords (true/false)
private val nullStyle: TextStyle = TextColors.gray         // base03 — comments / muted

private val plainJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * Pretty-prints a [JsonElement]. When [colorize] is `true`, applies a base16-style syntax
 * highlight via Mordant's named colours — the terminal's own colour scheme decides the exact
 * hex values, so a `base16_transparent` (or any base16) terminal theme lights up automatically.
 *
 * When [colorize] is `false` (typical for piped/redirected output), falls through to
 * `Json { prettyPrint = true }` so consumers like `jq` get clean, parseable JSON.
 */
fun renderJson(element: JsonElement, colorize: Boolean = true): String {
    if (!colorize) {
        return plainJson.encodeToString(JsonElement.serializer(), element)
    }
    return buildString { appendHighlighted(element, indent = "") }
}

private fun StringBuilder.appendHighlighted(element: JsonElement, indent: String) {
    when (element) {
        is JsonObject -> appendObject(element, indent)
        is JsonArray -> appendArray(element, indent)
        is JsonPrimitive -> appendPrimitive(element)
    }
}

private fun StringBuilder.appendObject(obj: JsonObject, indent: String) {
    if (obj.isEmpty()) {
        append("{}")
        return
    }
    append("{\n")
    val childIndent = "$indent  "
    val entries = obj.entries.toList()
    entries.forEachIndexed { idx, (key, value) ->
        append(childIndent)
        append(keyStyle(jsonString(key)))
        append(": ")
        appendHighlighted(value, childIndent)
        if (idx < entries.lastIndex) append(",")
        append("\n")
    }
    append(indent).append("}")
}

private fun StringBuilder.appendArray(arr: JsonArray, indent: String) {
    if (arr.isEmpty()) {
        append("[]")
        return
    }
    append("[\n")
    val childIndent = "$indent  "
    arr.forEachIndexed { idx, value ->
        append(childIndent)
        appendHighlighted(value, childIndent)
        if (idx < arr.lastIndex) append(",")
        append("\n")
    }
    append(indent).append("]")
}

private fun StringBuilder.appendPrimitive(p: JsonPrimitive) {
    if (p is JsonNull) {
        append(nullStyle("null"))
        return
    }
    val literal = Json.encodeToString(JsonPrimitive.serializer(), p)
    val styled = when {
        p.isString -> stringStyle(literal)
        p.content == "true" || p.content == "false" -> booleanStyle(literal)
        else -> numberStyle(literal)
    }
    append(styled)
}

private fun jsonString(s: String): String =
    Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(s))
