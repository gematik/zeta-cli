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
