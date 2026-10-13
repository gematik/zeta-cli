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

import de.gematik.zeta.stress.db.IdentityStore
import de.gematik.zeta.stress.db.PoppRow
import de.gematik.zeta.stress.db.PoppStore
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/** Outcome of an import run, for the CLI summary. */
data class PoppImportResult(val imported: Int, val matched: Int, val unmatched: Int)

/**
 * Imports off-band PoPP tokens (compact JWTs) from a directory into the `popp_token` cache. Each
 * file may hold one token per line. We read the claims via [PoppJwt] and bind each token to a corpus
 * identity by matching its `actorId` to `identity.telematik_id`.
 */
class PoppImporter(private val identities: IdentityStore, private val popp: PoppStore) {

    /** [onProgress] is called after each file with (files done, files total, tokens parsed so far). */
    fun importDir(dir: Path, onProgress: (Int, Int, Int) -> Unit = { _, _, _ -> }): PoppImportResult {
        require(dir.isDirectory()) { "$dir is not a directory" }
        val files = dir.listDirectoryEntries().filter { it.isRegularFile() }.sorted()
        require(files.isNotEmpty()) { "no token files found in $dir" }

        val rows = buildList {
            files.forEachIndexed { i, f ->
                f.toFile().readLines().map { it.trim() }.filter { it.isNotEmpty() }
                    .forEach { line -> parse(line)?.let { add(it) } }
                onProgress(i + 1, files.size, size)
            }
        }

        popp.insertAll(rows)
        val matched = rows.count { it.telematikId != null }
        return PoppImportResult(rows.size, matched, rows.size - matched)
    }

    private fun parse(token: String): PoppRow? {
        val c = PoppJwt.parse(token) ?: return null
        return PoppRow(
            telematikId = if (identities.get(c.actorId) != null) c.actorId else null,
            actorId = c.actorId,
            patientId = c.patientId,
            insurerId = c.insurerId,
            proofTime = c.proofTime,
            iat = c.iat,
            kid = c.kid,
            token = token,
        )
    }
}
