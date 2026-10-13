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

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.long

/**
 * How to reach a locally-read eGK, shared by `zeta popp standard` and `zeta serve`. [prefix]
 * namespaces the flags where a command already owns the plain names — `serve` spells them
 * `--popp-reader` and so on. The environment variables are the same either way.
 *
 * The card access number is deliberately not here: it belongs to one specific card, so only the
 * one-shot `zeta popp standard` can own it.
 */
internal class CardReaderOptions(prefix: String = "") : OptionGroup(name = "Card reader options") {

    /** The flag that pins a slot, for messages that have to tell the user which one to pass. */
    val readerOptionName = "--${prefix}reader"

    val connection: ConnectionType by option(
        "--${prefix}connection",
        metavar = "TYPE",
        envvar = "ZETA_POPP_CONNECTION",
        help = "Smartcard connection type: contact or contactless. Default: contact. " +
            "(env: ZETA_POPP_CONNECTION)",
    ).enum<ConnectionType>(ignoreCase = true).default(ConnectionType.CONTACT)

    val reader: String? by option(
        "--${prefix}reader",
        metavar = "NAME",
        envvar = "ZETA_POPP_READER",
        help = "PC/SC card reader name (substring match). Default: the first slot holding a card, " +
            "with a warning naming the others. (env: ZETA_POPP_READER)",
    )

    val waitSeconds: Long by option(
        "--${prefix}wait",
        metavar = "SECONDS",
        help = "Seconds to wait for a card when none is present. Default: 0 (fail immediately).",
    ).long().default(0)
}
