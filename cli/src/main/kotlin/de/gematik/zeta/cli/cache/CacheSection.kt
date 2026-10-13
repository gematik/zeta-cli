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
package de.gematik.zeta.cli.cache

/**
 * One kind of cached thing inside a [CacheDb] — today the VSDM bundles, tomorrow whatever else is
 * worth keeping between runs. A section owns its tables, creates them idempotently on construction,
 * and reports and prunes its own rows so `zeta ... cache stats` and the retention sweep do not need
 * to know what it stores.
 *
 * Sections are best-effort per operation: a failed read or write is a cache miss plus a warning,
 * never a failed command. Failing to *open* the database is fatal — a client told to cache that
 * silently isn't is worse than a startup error.
 */
interface CacheSection {
    /** Stable identifier, used in stats output. */
    val name: String

    fun entryCount(): Long

    /**
     * Retention and size bound; returns how many rows were removed. Nothing expires by freshness —
     * cached entries are revalidated against their source — so this is hygiene: it keeps a cache
     * bounded and stops personal data from lingering indefinitely.
     */
    fun prune(nowEpochSec: Long, maxEntries: Int, maxAgeDays: Int): Int
}

/**
 * Boundary a stored payload passes through, so encryption can be added without touching any
 * schema. Rows record the codec that wrote them; a row written by a codec this build does not have
 * is treated as a miss and dropped.
 */
interface BlobCodec {
    val name: String
    fun encode(plain: ByteArray): ByteArray
    fun decode(stored: ByteArray): ByteArray
}

object PlainBlobCodec : BlobCodec {
    override val name: String = "plain"
    override fun encode(plain: ByteArray): ByteArray = plain
    override fun decode(stored: ByteArray): ByteArray = stored
}
