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
package de.gematik.zeta.cli.popp

import de.gematik.zeta.catalog.Environment

/** The token-generation path served by the PoPP service (same across environments). */
internal const val POPP_TOKEN_PATH = "/popp/practitioner/api/v1/token-generation-ehc"

/**
 * The PoPP-service WebSocket URL for a TI [env], using the same `popp.<env>.poppservice.de` host-infix
 * scheme that [de.gematik.zeta.catalog.environmentFromIssuer] keys on. Callers still expose a
 * `--service-url` override for a non-standard host.
 */
internal fun poppServiceUrlFor(env: Environment): String =
    "wss://popp.${env.name.lowercase()}.poppservice.de$POPP_TOKEN_PATH"
