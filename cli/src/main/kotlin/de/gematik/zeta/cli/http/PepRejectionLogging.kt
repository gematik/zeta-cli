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
package de.gematik.zeta.cli.http

import de.gematik.zeta.cli.output.decodeCborToJson
import de.gematik.zeta.sdk.network.http.client.InnerHeadersKey
import de.gematik.zeta.sdk.network.http.client.InnerStatusKey
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.statement.HttpResponsePipeline

private val log = KotlinLogging.logger {}

/**
 * Log every inner ASL response the PEP rejected (`zeta-error-origin: pep`, status >= 400).
 *
 * The SDK answers those with token refresh and re-authentication and, when that does not help,
 * throws `StepUpFailed` without the response, so the PEP's reason never reaches the caller.
 * The interceptor runs on `Receive`, after the SDK's ASL decryption phase, and sees the plain
 * inner body.
 */
fun ZetaHttpClient.logPepRejections(): ZetaHttpClient = apply {
    delegate.responsePipeline.intercept(HttpResponsePipeline.Receive) { (_, body) ->
        val attributes = context.attributes
        val status = attributes.getOrNull(InnerStatusKey) ?: return@intercept
        val headers = attributes.getOrNull(InnerHeadersKey).orEmpty()
        if (status < 400 || !headers.value("zeta-error-origin").equals("pep", ignoreCase = true)) return@intercept
        val bytes = body as? ByteArray ?: return@intercept
        log.warn { "PEP rejected the request: HTTP $status ${describeBody(bytes, headers.value("Content-Type"))}" }
        logInnerAslResponse(status, "", headers, bytes)
    }
}

private fun Map<String, String>.value(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

private fun describeBody(bytes: ByteArray, contentType: String?): String {
    if (bytes.isEmpty()) return "(empty body)"
    val cbor = if (contentType?.contains("cbor", ignoreCase = true) == true) decodeCborToJson(bytes) else null
    return cbor?.toString()
        ?: bytes.decodeToString()
            .map { if (it.isISOControl() && it != '\n' && it != '\t') '�' else it }
            .joinToString("")
            .trim()
}
