package de.gematik.zeta.api

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Fetches OAuth Protected Resource Metadata (RFC 9728) from
 * `<baseUrl>/.well-known/oauth-protected-resource`.
 *
 * The [httpClient] is provided by the caller — typically the CLI or host application —
 * which is responsible for engine choice, timeouts, proxies, TLS, and lifecycle (closing
 * it on shutdown). [json] can be overridden for custom decoding behaviour.
 */
class ProtectedResourceClient(
    private val httpClient: HttpClient,
    private val json: Json = defaultMetadataJson,
) {
    suspend fun fetch(baseUrl: String): ProtectedResource =
        fetchWellKnownMetadata(
            httpClient = httpClient,
            base = baseUrl,
            path = WELL_KNOWN_PATH,
            json = json,
            decode = { raw -> json.decodeFromJsonElement<ProtectedResource>(raw).copy(raw = raw) },
            errorFactory = ::ProtectedResourceMetadataException,
        )

    companion object {
        const val WELL_KNOWN_PATH: String = "/.well-known/oauth-protected-resource"

        internal fun wellKnownUrl(baseUrl: String): String = wellKnownUrlOf(baseUrl, WELL_KNOWN_PATH)
    }
}

class ProtectedResourceMetadataException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
