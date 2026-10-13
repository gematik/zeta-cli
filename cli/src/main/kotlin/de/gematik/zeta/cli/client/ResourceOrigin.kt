/*
 * Copyright (Change Date see Readme), gematik GmbH
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the
 * European Commission – subsequent versions of the EUPL (the "Licence").
 * You may not use this work except in compliance with the Licence.
 *
 * You find a copy of the Licence in the "Licence" file or at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.
 * In case of changes by gematik GmbH find details in the "Readme" file.
 *
 * See the Licence for the specific language governing permissions and limitations under the Licence.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 */
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
