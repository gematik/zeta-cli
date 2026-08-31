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
