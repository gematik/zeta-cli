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
