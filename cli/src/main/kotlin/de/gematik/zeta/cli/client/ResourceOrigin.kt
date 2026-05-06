package de.gematik.zeta.cli.client

import io.ktor.http.Url

/**
 * Derive the OAuth `resource` (RFC 8707 — the resource-server identifier the access token
 * is bound to) from the URL of the actual request.
 *
 * The resource is the server's `scheme://host[:port]/` origin. For WebSocket URLs (`ws` /
 * `wss`) we map to `http` / `https` since the resource indicator is HTTP-shaped — the
 * Zeta Guard's auth realm is reachable over HTTPS regardless of how the protected endpoint
 * is opened.
 *
 * Default ports are dropped to match how Zeta Guard typically issues tokens; non-default
 * ports are preserved.
 */
internal fun originOf(url: String): String {
    val parsed = Url(url)
    val scheme = when (parsed.protocol.name) {
        "ws" -> "http"
        "wss" -> "https"
        else -> parsed.protocol.name
    }
    val port = parsed.port
    val portSuffix = if (port == parsed.protocol.defaultPort || port == 0 || port < 0) "" else ":$port"
    return "$scheme://${parsed.host}$portSuffix/"
}
