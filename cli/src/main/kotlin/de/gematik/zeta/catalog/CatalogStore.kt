package de.gematik.zeta.catalog

import kotlinx.serialization.Serializable

/**
 * Persistence seam for the service-discovery cache: one string slot per [Environment]. Kept
 * deliberately tiny and storage-agnostic so the `catalog` package carries no dependency on the
 * CLI's storage layer — the CLI supplies an implementation (e.g. over the profile database).
 * Implementations should be best-effort: a read/write failure is a cache miss, never an error.
 */
interface CatalogStore {
    fun read(env: Environment): String?
    fun write(env: Environment, value: String)
}

/** A cached catalog body plus the metadata needed to judge its freshness; serialized into a [CatalogStore]. */
@Serializable
data class CachedCatalog(
    val fetchedAtEpochSec: Long,
    val maxAgeSec: Long,
    val etag: String? = null,
    /** The raw `catalog.json` body, re-parsed on a hit so nothing is lost in a serialization round-trip. */
    val body: String,
) {
    fun ageSec(nowEpochSec: Long): Long = nowEpochSec - fetchedAtEpochSec
    fun isFresh(nowEpochSec: Long): Boolean = ageSec(nowEpochSec) < maxAgeSec
}
