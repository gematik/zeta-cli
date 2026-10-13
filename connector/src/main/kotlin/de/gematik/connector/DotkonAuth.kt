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
