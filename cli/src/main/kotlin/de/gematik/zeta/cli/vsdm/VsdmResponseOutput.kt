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
package de.gematik.zeta.cli.vsdm

/**
 * Render a VSDM response as an HTTP/1.1 message — status line, one `Name: Value` header per line, a
 * blank line, then the body verbatim. `zeta vsdm get -i` uses this so a pipe carries the response
 * headers (`ETag`, the VSDM `PZ` / Prüfziffer, …) alongside the bundle. Pure, so it's unit-testable.
 */
internal fun httpResponseText(
    status: Int,
    reason: String,
    headers: Map<String, String>,
    body: ByteArray,
): String = buildString {
    append("HTTP/1.1 ").append(status)
    if (reason.isNotBlank()) append(' ').append(reason)
    append('\n')
    headers.forEach { (name, value) -> append(name).append(": ").append(value).append('\n') }
    append('\n')
    append(body.decodeToString())
}
