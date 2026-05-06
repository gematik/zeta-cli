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
import kotlinx.serialization.json.JsonObject

/**
 * Shared plumbing for the OAuth/OIDC well-known metadata clients in this package
 * ([ProtectedResourceClient], [AuthorizationServerClient]). Each concrete client passes its
 * RFC well-known path, JSON decoder, and exception constructor; this file centralises the
 * URL normalisation, HTTP fetch, status check, and parse-error wrapping.
 */
private val log = KotlinLogging.logger("de.gematik.zeta.api.WellKnownMetadata")

/** Default permissive [Json] used when callers don't pass their own — matches the metadata clients. */
internal val defaultMetadataJson: Json = Json { ignoreUnknownKeys = true }

/** Normalise a base URL + well-known path per RFC 8414 §3 / RFC 9728 (trim trailing slash, append). */
internal fun wellKnownUrlOf(base: String, path: String): String =
    "${base.trimEnd('/')}$path"

/**
 * Fetch the well-known JSON document at `<base><path>` and decode it via [decode].
 *
 * The decoder receives the parsed `JsonObject` (rather than the raw text) so concrete
 * clients can both run kotlinx-serialization on it AND attach the same untyped object as
 * a `raw` field on their typed model — that's the contract `ProtectedResource` and
 * `AuthorizationServer` exposed before the refactor and we keep it intact.
 *
 * Any non-2xx status or parse failure is wrapped via [errorFactory] so each caller's
 * exception type stays distinct.
 */
internal suspend fun <T> fetchWellKnownMetadata(
    httpClient: HttpClient,
    base: String,
    path: String,
    json: Json,
    decode: (JsonObject) -> T,
    errorFactory: (message: String, cause: Throwable?) -> Throwable,
): T {
    val url = wellKnownUrlOf(base, path)
    log.debug { "Fetching well-known metadata from $url" }
    val response = httpClient.get(url) { accept(ContentType.Application.Json) }
    if (!response.status.isSuccess()) {
        throw errorFactory("Unexpected HTTP ${response.status.value} fetching $url", null)
    }
    return try {
        val raw = json.parseToJsonElement(response.bodyAsText())
        decode(raw as JsonObject)
    } catch (e: SerializationException) {
        throw errorFactory("Could not parse metadata from $url", e)
    } catch (e: ClassCastException) {
        throw errorFactory("Could not parse metadata from $url: response is not a JSON object", e)
    }
}
