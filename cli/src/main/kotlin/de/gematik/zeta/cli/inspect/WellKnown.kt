package de.gematik.zeta.cli.inspect

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

private val log = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/**
 * Fetches `<baseUrl>/.well-known/oauth-protected-resource` (RFC 9728). Returns both the
 * typed model (for text rendering) and the raw object (so `-o json` keeps unknown
 * extension fields the spec allows).
 */
suspend fun fetchProtectedResource(http: HttpClient, baseUrl: String): Pair<ProtectedResource, JsonObject> {
    val raw = fetchJsonObject(http, "${baseUrl.trimEnd('/')}/.well-known/oauth-protected-resource")
    return json.decodeFromJsonElement<ProtectedResource>(raw) to raw
}

/** Fetches `<issuer>/.well-known/oauth-authorization-server` (RFC 8414). */
suspend fun fetchAuthorizationServer(http: HttpClient, issuer: String): Pair<AuthorizationServer, JsonObject> {
    val raw = fetchJsonObject(http, "${issuer.trimEnd('/')}/.well-known/oauth-authorization-server")
    return json.decodeFromJsonElement<AuthorizationServer>(raw) to raw
}

private suspend fun fetchJsonObject(http: HttpClient, url: String): JsonObject {
    log.debug { "Fetching well-known metadata from $url" }
    val response = http.get(url) { accept(ContentType.Application.Json) }
    if (!response.status.isSuccess()) {
        error("Unexpected HTTP ${response.status.value} fetching $url")
    }
    return json.parseToJsonElement(response.bodyAsText()) as JsonObject
}
