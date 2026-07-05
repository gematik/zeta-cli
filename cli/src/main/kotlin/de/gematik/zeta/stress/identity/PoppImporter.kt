package de.gematik.zeta.stress.identity

import de.gematik.zeta.stress.db.IdentityStore
import de.gematik.zeta.stress.db.PoppRow
import de.gematik.zeta.stress.db.PoppStore
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val log = KotlinLogging.logger {}

/** Outcome of an import run, for the CLI summary. */
data class PoppImportResult(val imported: Int, val matched: Int, val unmatched: Int)

/**
 * Imports off-band PoPP tokens (compact JWTs) from a directory into the `popp_token` cache. Each
 * file may hold one token per line. We only base64url-decode the header + payload (no signature
 * check — these are opaque bearer artifacts we forward verbatim), read the claims, and bind each
 * token to a corpus identity by matching its `actorId` to `identity.telematik_id`.
 */
class PoppImporter(private val identities: IdentityStore, private val popp: PoppStore) {

    private val json = Json { ignoreUnknownKeys = true }

    fun importDir(dir: Path): PoppImportResult {
        require(dir.isDirectory()) { "$dir is not a directory" }
        val files = dir.listDirectoryEntries().filter { it.isRegularFile() }.sorted()
        require(files.isNotEmpty()) { "no token files found in $dir" }

        val rows = files.asSequence()
            .flatMap { f -> f.toFile().readLines().asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { parse(it) }
            .toList()

        popp.insertAll(rows)
        val matched = rows.count { it.telematikId != null }
        return PoppImportResult(rows.size, matched, rows.size - matched)
    }

    private fun parse(token: String): PoppRow? {
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
            val actorId = str("actorId") ?: run { log.warn { "token missing actorId" }; return null }
            val patientId = str("patientId") ?: run { log.warn { "token missing patientId" }; return null }
            val insurerId = str("insurerId") ?: run { log.warn { "token missing insurerId" }; return null }
            PoppRow(
                telematikId = if (identities.get(actorId) != null) actorId else null,
                actorId = actorId,
                patientId = patientId,
                insurerId = insurerId,
                proofTime = num("patientProofTime"),
                iat = num("iat"),
                kid = header["kid"]?.jsonPrimitive?.content,
                token = token,
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
