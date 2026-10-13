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
package de.gematik.zeta.cli.state

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles
import de.gematik.zeta.sdk.SdkStatus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * A one-glance verdict for a lifecycle command (`discover` / `register` / `authenticate` /
 * `login` / `logout`): did the operation reach the state it was after, plus the few facts that
 * matter — endpoint, requested scopes, authorization server, and the resulting [SdkStatus].
 *
 * [ok] is the command's own success criterion, deliberately distinct from [status]: `login` and
 * `authenticate` succeed once any usable credential (access *or* refresh token) is present;
 * `register` once a client registration exists; `logout` once the tokens are gone. See the
 * per-command wiring and [hasUsableCredentials] / [isRegistered].
 */
internal data class CommandResult(
    val operation: String,
    val ok: Boolean,
    val endpoint: String,
    val scopes: List<String>,
    val authServer: String?,
    val status: SdkStatus?,
    val detail: String? = null,
)

/** Usable enough to call it authenticated: an access token, or a refresh token to mint one. */
internal val SdkStatus.hasUsableCredentials: Boolean
    get() = this == SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN || this == SdkStatus.HAS_REFRESH_TOKEN

/** A dynamic-client registration exists (client_id present), regardless of token state. */
internal val SdkStatus.isRegistered: Boolean
    get() = this != SdkStatus.NOT_REGISTERED

private data class Row(val label: String, val value: String, val color: TextStyle?)

/**
 * Compact block: a green `✓` / red `✗` verdict header with the operation and endpoint, then the
 * handful of facts as aligned `label: value` rows. The verdict colour is reused for the status
 * value so ok/not-ok reads at a glance; the optional note is muted.
 */
internal fun renderResultText(result: CommandResult, colorize: Boolean): String = buildString {
    val verdictColor: TextStyle = if (result.ok) TextColors.green else TextColors.red
    val head = "${if (result.ok) "✓" else "✗"} ${result.operation}"
    val styledHead = if (colorize) (verdictColor + TextStyles.bold)(head) else head
    appendLine("$styledHead  ${result.endpoint}")

    val rows = buildList {
        if (result.scopes.isNotEmpty()) add(Row("scopes", result.scopes.joinToString(" "), null))
        result.authServer?.let { add(Row("auth server", it, null)) }
        result.status?.let { add(Row("status", it.name, verdictColor)) }
        result.detail?.let { add(Row("note", it, TextColors.gray)) }
    }
    val width = rows.maxOfOrNull { it.label.length + 1 } ?: 0
    rows.forEach { row ->
        val label = "${row.label}:"
        val pad = " ".repeat(width - label.length)
        val styledLabel = if (colorize) TextColors.gray(label) else label
        val styledValue = if (colorize) (row.color ?: TextColors.green)(row.value) else row.value
        appendLine("  $styledLabel$pad $styledValue")
    }
}.trimEnd()

internal fun renderResultJson(result: CommandResult): JsonElement = buildJsonObject {
    put("operation", JsonPrimitive(result.operation))
    put("ok", JsonPrimitive(result.ok))
    put("endpoint", JsonPrimitive(result.endpoint))
    put("scopes", buildJsonArray { result.scopes.forEach { add(JsonPrimitive(it)) } })
    put("authorization_server", result.authServer?.let(::JsonPrimitive) ?: JsonNull)
    put("status", result.status?.name?.let(::JsonPrimitive) ?: JsonNull)
    result.detail?.let { put("detail", JsonPrimitive(it)) }
}
