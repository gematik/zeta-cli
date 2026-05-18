package de.gematik.zeta.cli.popp

import com.github.ajalt.clikt.core.CliktCommand
import de.gematik.zeta.cli.output.OutputFormat
import de.gematik.zeta.cli.output.renderJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * `text` and `raw` both emit the JWT verbatim — the typical consumer pipes it into
 * `--popp-token` / `PoPP:` header where the compact serialisation is required. `json`
 * pretty-prints the decoded JOSE header + payload (segments that fail to decode are
 * silently omitted; popp is the authority on token validity, not us).
 */
internal fun CliktCommand.emitPoppToken(token: String, outputFormat: OutputFormat, colorize: Boolean) {
    when (outputFormat) {
        OutputFormat.RAW, OutputFormat.TEXT -> echo(token)
        OutputFormat.JSON -> echo(renderJson(buildTokenJson(token), colorize = colorize))
    }
}

private fun buildTokenJson(token: String): JsonObject = buildJsonObject {
    decodeJwtSegment(token, segment = 0)?.let { put("header", it) }
    decodeJwtSegment(token, segment = 1)?.let { put("payload", it) }
}

/**
 * Decode a single segment (`0` = JOSE header, `1` = payload) of a JWT compact serialisation
 * into a [JsonElement]. Returns `null` for malformed input.
 */
private fun decodeJwtSegment(token: String, segment: Int): JsonElement? = runCatching {
    val parts = token.split('.')
    require(segment in parts.indices) { "JWT segment $segment out of range (got ${parts.size} parts)" }
    val padded = parts[segment] + "=".repeat((4 - parts[segment].length % 4) % 4)
    val bytes = Base64.getUrlDecoder().decode(padded)
    Json.parseToJsonElement(bytes.decodeToString())
}.getOrNull()
