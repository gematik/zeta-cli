package de.gematik.zeta.api

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

private val log = KotlinLogging.logger {}

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
    private val json: Json = defaultJson,
) {
    suspend fun fetch(baseUrl: String): ProtectedResource {
        val url = wellKnownUrl(baseUrl)
        log.debug { "Fetching protected resource metadata from $url" }
        val response = httpClient.get(url) {
            accept(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            throw ProtectedResourceMetadataException(
                "Unexpected HTTP ${response.status.value} fetching $url",
            )
        }
        return try {
            // Decode through JsonObject so we can keep the raw payload alongside the typed view.
            val raw = json.parseToJsonElement(response.bodyAsText()).jsonObject
            json.decodeFromJsonElement<ProtectedResource>(raw).copy(raw = raw)
        } catch (e: SerializationException) {
            throw ProtectedResourceMetadataException("Could not parse metadata from $url", e)
        }
    }

    companion object {
        const val WELL_KNOWN_PATH: String = "/.well-known/oauth-protected-resource"

        private val defaultJson: Json = Json {
            ignoreUnknownKeys = true
        }

        internal fun wellKnownUrl(baseUrl: String): String =
            "${baseUrl.trimEnd('/')}$WELL_KNOWN_PATH"
    }
}

class ProtectedResourceMetadataException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
