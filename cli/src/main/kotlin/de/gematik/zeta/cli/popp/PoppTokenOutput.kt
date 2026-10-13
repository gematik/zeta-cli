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
