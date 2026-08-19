package de.gematik.zeta.catalog

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

private val log = KotlinLogging.logger {}

/** Raised when the service-discovery catalog can't be fetched or parsed. */
class CatalogException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads the TI service-discovery catalog. Takes a pre-configured [http] client — proxy and timeouts
 * are the caller's responsibility; this client never builds its own.
 *
 * When a [store] is supplied the catalog is cached and honoured for the server's
 * `Cache-Control: max-age` (an unset value falls back to [DEFAULT_TTL_SEC]): a fresh entry is served
 * without any network call. If discovery is unavailable and any cached copy exists — even a stale
 * one — the fetch failure is downgraded to a warning and the cached catalog is used, since a
 * momentarily-unreachable catalog should not fail a read. Only a failure with no cache at all is an
 * error.
 */
class ServiceDiscoveryClient(
    private val http: HttpClient,
    private val store: CatalogStore? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchCatalog(env: Environment): ServiceCatalog {
        val url = env.catalogUrl
        val now = System.currentTimeMillis() / 1000
        val cached = loadCached(env)

        if (cached != null && cached.isFresh(now)) {
            log.debug {
                "using cached service-discovery catalog for ${env.name.lowercase()} " +
                    "(age ${cached.ageSec(now)}s, ttl ${cached.maxAgeSec}s)"
            }
            return parse(url, cached.body)
        }

        log.debug { "Fetching service-discovery catalog: $url" }
        return try {
            val resp = http.get(url)
            if (!resp.status.isSuccess()) {
                throw CatalogException("catalog fetch failed: $url returned HTTP ${resp.status.value}")
            }
            val body = resp.bodyAsText()
            val catalog = parse(url, body)
            val maxAge = parseMaxAge(resp.headers[HttpHeaders.CacheControl]) ?: DEFAULT_TTL_SEC
            storeCached(env, CachedCatalog(now, maxAge, resp.headers[HttpHeaders.ETag], body))
            log.info {
                "catalog ${env.name.lowercase()}: env=${catalog.env} updated_at=${catalog.updatedAt} " +
                    "instances=${catalog.serviceInstances.size} (cached ${maxAge}s)"
            }
            catalog
        } catch (e: Exception) {
            if (cached != null) {
                log.warn {
                    "service-discovery unavailable ($url): ${e.message}; " +
                        "using cached catalog (age ${cached.ageSec(now)}s)"
                }
                return parse(url, cached.body)
            }
            if (e is CatalogException) throw e
            throw CatalogException(
                "could not reach service-discovery at $url and no cached catalog is available: ${e.message}",
                e,
            )
        }
    }

    private fun parse(url: String, body: String): ServiceCatalog =
        try {
            json.decodeFromString(ServiceCatalog.serializer(), body)
        } catch (e: Exception) {
            throw CatalogException("could not parse catalog.json from $url: ${e.message}", e)
        }

    private fun loadCached(env: Environment): CachedCatalog? =
        store?.read(env)?.let { text ->
            runCatching { json.decodeFromString(CachedCatalog.serializer(), text) }.getOrNull()
        }

    private fun storeCached(env: Environment, cached: CachedCatalog) {
        store?.write(env, json.encodeToString(CachedCatalog.serializer(), cached))
    }

    private companion object {
        /** Fallback TTL when the response carries no usable `max-age`. */
        const val DEFAULT_TTL_SEC = 3600L

        private val MAX_AGE = Regex("""max-age\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)

        /** Parse `max-age` (seconds) from a `Cache-Control` header; `no-store`/`no-cache` mean don't reuse. */
        fun parseMaxAge(cacheControl: String?): Long? {
            cacheControl ?: return null
            if (cacheControl.contains("no-store", ignoreCase = true) ||
                cacheControl.contains("no-cache", ignoreCase = true)
            ) {
                return 0
            }
            return MAX_AGE.find(cacheControl)?.groupValues?.get(1)?.toLongOrNull()
        }
    }
}
