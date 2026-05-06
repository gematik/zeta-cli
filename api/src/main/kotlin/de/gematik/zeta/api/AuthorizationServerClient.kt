package de.gematik.zeta.api

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Fetches OAuth Authorization Server Metadata (RFC 8414) from
 * `<issuer>/.well-known/oauth-authorization-server`.
 *
 * The trailing slash on [issuer] is normalised before appending the well-known path,
 * matching the rule in RFC 8414 §3.
 */
class AuthorizationServerClient(
    private val httpClient: HttpClient,
    private val json: Json = defaultMetadataJson,
) {
    suspend fun fetch(issuer: String): AuthorizationServer =
        fetchWellKnownMetadata(
            httpClient = httpClient,
            base = issuer,
            path = WELL_KNOWN_PATH,
            json = json,
            decode = { raw -> json.decodeFromJsonElement<AuthorizationServer>(raw).copy(raw = raw) },
            errorFactory = ::AuthorizationServerMetadataException,
        )

    companion object {
        const val WELL_KNOWN_PATH: String = "/.well-known/oauth-authorization-server"

        internal fun wellKnownUrl(issuer: String): String = wellKnownUrlOf(issuer, WELL_KNOWN_PATH)
    }
}

class AuthorizationServerMetadataException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
