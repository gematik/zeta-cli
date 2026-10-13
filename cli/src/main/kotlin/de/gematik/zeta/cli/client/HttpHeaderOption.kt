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

import com.github.ajalt.clikt.core.UsageError

/**
 * Parse a single curl-style `-H "Name: Value"` argument into a name/value pair. Trims both
 * sides; throws [UsageError] on malformed input so Clikt surfaces a one-line usage message.
 */
internal fun parseHeaderOption(value: String): Pair<String, String> {
    val idx = value.indexOf(':')
    if (idx <= 0) throw UsageError("invalid -H value: '$value' (expected 'Name: Value')")
    return value.substring(0, idx).trim() to value.substring(idx + 1).trim()
}

/**
 * HTTP header that carries a gematik Proof of Patient Presence (PoPP) token to a
 * Zeta-protected resource. Defined by the gematik ZETA spec (A_25669) —
 * https://gemspec.gematik.de/docs/gemSpec/gemSpec_ZETA/latest/#A_25669
 */
internal const val POPP_HEADER_NAME = "PoPP"
