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
package de.gematik.zeta.catalog

import java.net.URI

/** A TI platform environment and the service-discovery host that serves its catalog. */
enum class Environment(val host: String) {
    DEV("service-discovery.dev.ti-platform.de"),
    REF("service-discovery.ref.ti-platform.de"),
    TEST("service-discovery.test.ti-platform.de"),
    PROD("service-discovery.prod.ti-platform.de"),
    ;

    val catalogUrl: String get() = "https://$host/catalog.json"
}

/**
 * Derives the environment from a PoPP token's `iss` by the infix in its host — `.dev.`, `.ref.`,
 * `.test.`, `.prod.`. Returns null when none matches (the caller treats that as an error).
 */
fun environmentFromIssuer(iss: String): Environment? {
    val host = runCatching { URI(iss).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: iss
    return when {
        ".dev." in host -> Environment.DEV
        ".ref." in host -> Environment.REF
        ".test." in host -> Environment.TEST
        ".prod." in host -> Environment.PROD
        else -> null
    }
}
