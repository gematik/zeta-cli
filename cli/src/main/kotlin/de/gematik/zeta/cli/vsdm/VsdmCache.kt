package de.gematik.zeta.cli.vsdm

import de.gematik.zeta.catalog.Environment
import de.gematik.zeta.stress.identity.PoppClaims

/**
 * Identity of one cached VSDM bundle.
 *
 * An ETag names a version of a patient record, so the key is that record's business identity —
 * insurer plus insurant, from the PoPP token — not a hash of the response. [endpointOrigin] and
 * [profileVersion] separate records read from different services or profiles; [contentType]
 * separates serializations of the same version, which share an ETag but not a body. A future
 * profile version may well render the same record differently, so it stays in the key.
 *
 * [actorId] scopes every entry to the SMC-B that read it, so several identities can share one
 * cache file and still be counted and purged apart. It buys attribution, not isolation: the file
 * itself is shared, and anyone who can read it reads every bundle in it. Give each tenant its own
 * `--cache-db` where that matters.
 *
 * The environment is not part of the key — [endpointOrigin] already implies it, since each
 * environment's catalog resolves to different hosts. Where it does not (an explicit `--endpoint`),
 * it is the same server handing out the same ETags, so sharing is correct rather than wrong.
 */
data class VsdmCacheKey(
    val actorId: String,
    val endpointOrigin: String,
    val insurerId: String,
    val insurantId: String,
    val profileVersion: String,
    val contentType: String,
)

// `insurantId` is the token's `patientId` claim: PoPP calls the person a patient because it
// proves their presence, VSDM calls them the insurant whose master data this is.
fun cacheKeyFor(
    claims: PoppClaims,
    endpointOrigin: String,
    profileVersion: String,
    contentType: String,
): VsdmCacheKey = VsdmCacheKey(
    actorId = claims.actorId,
    endpointOrigin = endpointOrigin,
    insurerId = claims.insurerId,
    insurantId = claims.patientId,
    profileVersion = profileVersion,
    contentType = contentType,
)

/**
 * The media type to key on, or `null` when the request does not pin one down. A lookup needs the
 * type *before* the response exists, so it comes from `Accept`; a store uses the response's own
 * `Content-Type`. When `Accept` is a wildcard or a list, no single type is implied — the entry is
 * still stored under whatever the service actually returned, and the next request naming that
 * type finds it.
 */
fun normalizedContentType(raw: String?): String? {
    val first = raw?.substringBefore(',')?.substringBefore(';')?.trim()?.lowercase()
    return first?.takeIf { it.isNotEmpty() && it != "*/*" && !it.endsWith("/*") }
}

/**
 * A cached bundle plus what is needed to revalidate it and to reason about its age. [env] is
 * carried along rather than keyed on, so a row says which environment it came from when someone
 * reads the file with `sqlite3`.
 */
data class CachedBundle(
    val etag: String,
    val body: ByteArray,
    val env: Environment,
    val poppToken: String?,
    val fetchedAtEpochSec: Long,
    val revalidatedAtEpochSec: Long,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is CachedBundle &&
                etag == other.etag &&
                body.contentEquals(other.body) &&
                env == other.env &&
                poppToken == other.poppToken &&
                fetchedAtEpochSec == other.fetchedAtEpochSec &&
                revalidatedAtEpochSec == other.revalidatedAtEpochSec
            )

    override fun hashCode(): Int {
        var result = etag.hashCode()
        result = 31 * result + body.contentHashCode()
        result = 31 * result + env.hashCode()
        result = 31 * result + (poppToken?.hashCode() ?: 0)
        result = 31 * result + fetchedAtEpochSec.hashCode()
        result = 31 * result + revalidatedAtEpochSec.hashCode()
        return result
    }
}

/**
 * Persistence seam for the bundle cache. Implementations are best-effort per operation: a failed
 * read or write is a cache miss plus a warning, never a failed command. Failing to *open* the
 * store is fatal — a client told to cache that silently isn't is worse than a startup error.
 */
interface VsdmCacheStore {
    fun lookup(key: VsdmCacheKey): CachedBundle?
    fun store(key: VsdmCacheKey, entry: CachedBundle)

    /** Record that a `304` confirmed the stored version, refreshing its clock and PoPP token. */
    fun touch(key: VsdmCacheKey, nowEpochSec: Long, poppToken: String?)
    fun invalidate(key: VsdmCacheKey)
}

/** What we decided to do about the cache before issuing the read. */
enum class CacheIntent {
    /** No client condition and we hold a version: ask for it, serve our copy on `304`. */
    SERVE_FROM_CACHE,

    /** No client condition and we hold nothing: read in full and fill the cache. */
    FILL,

    /** The client named exactly the version we hold — its conditional request, its answer. */
    CLIENT_REVALIDATE,

    /** The client named a different version, or asked us not to use a cache. */
    BYPASS,

    /** Caching is not configured. */
    OFF,
}

/** [intent] plus the `If-None-Match` value that goes upstream, which is never null once caching is on. */
data class CacheDecision(val intent: CacheIntent, val ifNoneMatch: String?)

/**
 * The conditional-request rule, in one place.
 *
 * With caching off the request is passed through untouched — including the absence of a header,
 * which the VSDM service answers with `428`. That keeps the daemon's documented behaviour exactly
 * as it was for anyone who has not opted in.
 *
 * With caching on, a client that asked a conditional question always gets its own question asked
 * and its own answer back; only a client that asked none lets us substitute our stored version.
 */
fun cacheDecision(
    clientIfNoneMatch: String?,
    cachedEtag: String?,
    cacheControl: String?,
    cacheEnabled: Boolean,
): CacheDecision {
    if (!cacheEnabled) return CacheDecision(CacheIntent.OFF, clientIfNoneMatch)

    val directives = cacheControl?.lowercase().orEmpty()
    if ("no-cache" in directives || "no-store" in directives) {
        return CacheDecision(CacheIntent.BYPASS, clientIfNoneMatch ?: NO_KNOWN_VERSION_ETAG)
    }

    return when {
        clientIfNoneMatch == null && cachedEtag != null ->
            CacheDecision(CacheIntent.SERVE_FROM_CACHE, cachedEtag)

        clientIfNoneMatch == null ->
            CacheDecision(CacheIntent.FILL, NO_KNOWN_VERSION_ETAG)

        clientIfNoneMatch == cachedEtag ->
            CacheDecision(CacheIntent.CLIENT_REVALIDATE, clientIfNoneMatch)

        else -> CacheDecision(CacheIntent.BYPASS, clientIfNoneMatch)
    }
}

/** Value for the `middleware-cache` header, once the upstream status is known. */
fun cacheOutcome(intent: CacheIntent, upstreamStatus: Int): String = when (intent) {
    CacheIntent.OFF -> "off"
    CacheIntent.BYPASS -> "bypass"
    CacheIntent.CLIENT_REVALIDATE -> "revalidated"
    CacheIntent.FILL -> "miss"
    // We asked with our stored version: 304 confirms it, anything else means it was stale.
    CacheIntent.SERVE_FROM_CACHE -> if (upstreamStatus == HTTP_NOT_MODIFIED) "hit" else "miss"
}

/** True when the cached body should be served in place of the (bodyless) upstream response. */
fun servesFromCache(intent: CacheIntent, upstreamStatus: Int): Boolean =
    intent == CacheIntent.SERVE_FROM_CACHE && upstreamStatus == HTTP_NOT_MODIFIED

internal const val MIDDLEWARE_CACHE_HEADER = "middleware-cache"
internal const val MIDDLEWARE_UPSTREAM_STATUS_HEADER = "middleware-upstream-status"
internal const val MIDDLEWARE_INSURER_ID_HEADER = "middleware-insurer-id"
internal const val MIDDLEWARE_INSURANT_ID_HEADER = "middleware-insurant-id"

/**
 * "I hold no version of this record". The VSDM service requires `If-None-Match`, so this goes out
 * whenever no cached ETag applies; it never matches, and the service sends the full bundle.
 */
internal const val NO_KNOWN_VERSION_ETAG =
    "\"0000000000000000000000000000000000000000000000000000000000000000\""

internal const val HTTP_NOT_MODIFIED = 304
