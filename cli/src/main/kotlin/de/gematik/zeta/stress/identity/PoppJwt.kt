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
package de.gematik.zeta.stress.identity

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val log = KotlinLogging.logger {}

/** The claims a PoPP token carries: acting identity, insurant, insurance, and timing. */
data class PoppClaims(
    val actorId: String,
    val patientId: String,
    val insurerId: String,
    val proofTime: Long?,
    val iat: Long?,
    val kid: String?,
    val iss: String?,
)

/**
 * Decodes a compact PoPP JWT's claims. Only the base64url header + payload are read (no signature
 * check — these are opaque bearer artifacts forwarded verbatim). Returns null on a malformed token
 * or a missing required claim.
 */
object PoppJwt {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(token: String): PoppClaims? {
        val parts = token.split('.')
        if (parts.size < 2) {
            log.warn { "skipping non-JWT token line (${parts.size} segments)" }
            return null
        }
        return try {
            val header = json.parseToJsonElement(String(b64url(parts[0]))).jsonObject
            val payload = json.parseToJsonElement(String(b64url(parts[1]))).jsonObject
            fun str(k: String) = payload[k]?.jsonPrimitive?.content
            fun num(k: String) = payload[k]?.jsonPrimitive?.longOrNull
            PoppClaims(
                actorId = str("actorId") ?: run { log.warn { "token missing actorId" }; return null },
                patientId = str("patientId") ?: run { log.warn { "token missing patientId" }; return null },
                insurerId = str("insurerId") ?: run { log.warn { "token missing insurerId" }; return null },
                proofTime = num("patientProofTime"),
                iat = num("iat"),
                kid = header["kid"]?.jsonPrimitive?.content,
                iss = str("iss"),
            )
        } catch (e: Exception) {
            log.warn { "failed to parse PoPP token: ${e.message}" }
            null
        }
    }

    private fun b64url(s: String): ByteArray {
        val pad = when (s.length % 4) { 2 -> "=="; 3 -> "="; else -> "" }
        return Base64.getUrlDecoder().decode(s + pad)
    }
}
