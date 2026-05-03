package de.gematik.connector

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Apply the non-TLS auth portion of [dotkon] — pre-emptive HTTP basic auth via a
 * `defaultRequest` header. PKCS#12 credentials are TLS client certs and are handled
 * elsewhere (in the per-engine TLS bridge); this is a no-op for them.
 *
 * Engine-agnostic: works inside any `HttpClient(<Engine>) { dotkonAuth(dotkon) }` block.
 */
fun HttpClientConfig<*>.dotkonAuth(dotkon: Dotkon) {
    val basic = dotkon.credentials as? Credentials.Basic ?: return
    val token = basicAuthHeader(basic.username, basic.password)
    defaultRequest {
        headers {
            // Set, not append, so a second install replaces the previous credential.
            set(HttpHeaders.Authorization, token)
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun basicAuthHeader(user: String, pass: String): String =
    "Basic " + Base64.Default.encode("$user:$pass".toByteArray())
