package de.gematik.zeta.cli.popp

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

private val log = KotlinLogging.logger {}

/**
 * Decode the `transactionResult` returned by `CardService.SecureSendAPDU` (v8.2.0) into the
 * list of hex APDU responses popp's `ScenarioResponseMessage.steps` expects.
 *
 * In v8.2.0 the field is just an opaque string. Strong evidence (matching the v8.2.1
 * schema's typed `SignedScenarioResponse.responseApduList.responseApdu` shape, plus the
 * symmetry with the input `transactionData` which is itself a JWT carrying scenario steps)
 * says the Connector returns a JWT whose payload nests an array of hex APDU response
 * strings. The exact field name(s) aren't documented for v8.2.0, so we walk the parsed
 * JSON object looking for the first string-array under any key whose name mentions APDU
 * (case-insensitive). Robust to the casing/nesting variants gematik schemas tend to use.
 *
 * If the format ever stops fitting the assumptions here, the resulting [IllegalStateException]
 * carries the full payload at DEBUG so the actual shape is one log line away.
 */
internal fun parseSecureSendApduResult(transactionResult: String): List<String> {
    val payload = decodePayload(transactionResult)
    log.debug { "SecureSendAPDU transactionResult payload: $payload" }
    return findApduList(payload)
        ?: error(
            "could not extract APDU response list from transactionResult; " +
                "raise -vv to see the parsed payload",
        )
}

/**
 * Try the input as a JWT compact serialisation first (`<header>.<payload>.<signature>`,
 * payload base64url-encoded JSON). Fall back to treating the whole thing as a JSON object
 * — mid-2026 production Connectors might switch the encoding without bumping the WSDL
 * version.
 */
private fun decodePayload(transactionResult: String): JsonElement {
    val parts = transactionResult.split('.')
    if (parts.size == 3) {
        runCatching {
            val padded = parts[1] + "=".repeat((4 - parts[1].length % 4) % 4)
            val bytes = Base64.getUrlDecoder().decode(padded)
            return Json.parseToJsonElement(bytes.decodeToString())
        }
    }
    return Json.parseToJsonElement(transactionResult)
}

/**
 * BFS for the first `JsonArray` of `JsonPrimitive` strings whose owning key contains
 * "apdu" (case-insensitive). Returns `null` if none found.
 */
private fun findApduList(root: JsonElement): List<String>? {
    val queue = ArrayDeque<JsonElement>().apply { add(root) }
    while (queue.isNotEmpty()) {
        val el = queue.removeFirst()
        when (el) {
            is JsonObject -> {
                for ((key, value) in el) {
                    if (key.contains("apdu", ignoreCase = true) && value is JsonArray && value.isStringList()) {
                        return value.map { (it as JsonPrimitive).content }
                    }
                    queue.add(value)
                }
            }
            is JsonArray -> el.forEach { queue.add(it) }
            else -> { /* primitives can't contain children */ }
        }
    }
    return null
}

private fun JsonArray.isStringList(): Boolean =
    all { it is JsonPrimitive && it.isString }
