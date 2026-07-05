package de.gematik.zeta.catalog

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

private val log = KotlinLogging.logger {}

/** Raised when the service-discovery catalog can't be fetched or parsed. */
class CatalogException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads the TI service-discovery catalog. Takes a pre-configured [http] client — caching, proxy and
 * timeouts are the caller's responsibility; this client never builds its own.
 */
class ServiceDiscoveryClient(private val http: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchCatalog(env: Environment): ServiceCatalog {
        val url = env.catalogUrl
        log.debug { "Fetching service-discovery catalog: $url" }
        val body = try {
            val resp = http.get(url)
            if (!resp.status.isSuccess()) {
                throw CatalogException("catalog fetch failed: $url returned HTTP ${resp.status.value}")
            }
            resp.bodyAsText()
        } catch (e: CatalogException) {
            throw e
        } catch (e: Exception) {
            throw CatalogException("could not reach service-discovery at $url: ${e.message}", e)
        }
        val catalog = try {
            json.decodeFromString(ServiceCatalog.serializer(), body)
        } catch (e: Exception) {
            throw CatalogException("could not parse catalog.json from $url: ${e.message}", e)
        }
        log.info { "catalog ${env.name.lowercase()}: env=${catalog.env} updated_at=${catalog.updatedAt} " +
            "instances=${catalog.serviceInstances.size}" }
        return catalog
    }
}
