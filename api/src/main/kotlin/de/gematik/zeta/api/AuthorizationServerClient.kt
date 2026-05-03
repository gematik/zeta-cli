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
 * Fetches OAuth Authorization Server Metadata (RFC 8414) from
 * `<issuer>/.well-known/oauth-authorization-server`.
 *
 * The trailing slash on [issuer] is normalised before appending the well-known path,
 * matching the rule in RFC 8414 §3.
 */
class AuthorizationServerClient(
    private val httpClient: HttpClient,
    private val json: Json = defaultJson,
) {
    suspend fun fetch(issuer: String): AuthorizationServer {
        val url = wellKnownUrl(issuer)
        log.debug { "Fetching authorization server metadata from $url" }
        val response = httpClient.get(url) {
            accept(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            throw AuthorizationServerMetadataException(
                "Unexpected HTTP ${response.status.value} fetching $url",
            )
        }
        return try {
            val raw = json.parseToJsonElement(response.bodyAsText()).jsonObject
            json.decodeFromJsonElement<AuthorizationServer>(raw).copy(raw = raw)
        } catch (e: SerializationException) {
            throw AuthorizationServerMetadataException("Could not parse metadata from $url", e)
        }
    }

    companion object {
        const val WELL_KNOWN_PATH: String = "/.well-known/oauth-authorization-server"

        private val defaultJson: Json = Json {
            ignoreUnknownKeys = true
        }

        internal fun wellKnownUrl(issuer: String): String =
            "${issuer.trimEnd('/')}$WELL_KNOWN_PATH"
    }
}

class AuthorizationServerMetadataException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
