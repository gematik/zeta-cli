package de.gematik.zeta.cli.popp

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.HexFormat
import javax.smartcardio.Card
import javax.smartcardio.CardChannel
import javax.smartcardio.CardException
import javax.smartcardio.CardTerminal
import javax.smartcardio.CommandAPDU
import javax.smartcardio.TerminalFactory

private val log = KotlinLogging.logger {}
private val HEX = HexFormat.of()

/**
 * A physically inserted eGK reached over a **contact** PC/SC reader. Executes PoPP
 * Standard-scenario APDUs directly on the card via `javax.smartcardio`.
 *
 * Contact readers only — contactless readers require a PACE channel (card access number),
 * which is out of scope here. The APDU exchange mirrors the `kartos` simulator's contract:
 * a command APDU hex in, the full response APDU (`data ‖ SW`) hex out, so it drops straight
 * into [PoppClient.runStandardScenario].
 */
internal class SmartCard private constructor(
    private val card: Card,
    private val channel: CardChannel,
    private val terminalName: String,
) : AutoCloseable {

    fun exchange(step: ScenarioStep): String {
        val commandHex = step.commandApdu.filterNot { it.isWhitespace() }
        log.debug { "card -> $commandHex" }
        val response = try {
            channel.transmit(CommandAPDU(HEX.parseHex(commandHex)))
        } catch (e: CardException) {
            throw CardException("APDU transmit failed on '$terminalName'", e)
        }
        val sw = "%04x".format(response.sw)
        if (step.expectedStatusWords.isNotEmpty() &&
            step.expectedStatusWords.none { it.equals(sw, ignoreCase = true) }
        ) {
            // The server is the authority on scenario success, so don't abort — just flag it.
            log.warn { "card returned status word $sw, expected ${step.expectedStatusWords} for $commandHex" }
        }
        val responseHex = HEX.formatHex(response.bytes)
        log.debug { "card <- $responseHex" }
        return responseHex
    }

    override fun close() {
        runCatching { card.disconnect(false) }
    }

    companion object {
        /**
         * Select a PC/SC terminal — by [readerName] substring, else the one with a card present —
         * connect over `T=1`, and return a ready [SmartCard]. When no card is present and no name
         * was given, wait up to [waitSeconds] for one in the first terminal (0 = don't wait).
         */
        fun connect(readerName: String?, waitSeconds: Long): SmartCard {
            val terminals = try {
                TerminalFactory.getDefault().terminals().list()
            } catch (e: CardException) {
                throw CardException(
                    "no PC/SC smartcard service available — is a reader connected" +
                        " (and pcscd running on Linux)?",
                    e,
                )
            }
            if (terminals.isEmpty()) throw CardException("no smartcard readers found")
            log.debug { "available card readers: ${terminals.joinToString { it.name }}" }

            val terminal = selectTerminal(terminals, readerName, waitSeconds)
            log.info { "using card reader: ${terminal.name}" }
            val card = terminal.connect("T=1")
            return SmartCard(card, card.basicChannel, terminal.name)
        }

        private fun selectTerminal(
            terminals: List<CardTerminal>,
            readerName: String?,
            waitSeconds: Long,
        ): CardTerminal {
            if (readerName != null) {
                return terminals.firstOrNull { it.name.contains(readerName, ignoreCase = true) }
                    ?: throw CardException(
                        "no reader matching '$readerName'; available: ${terminals.joinToString { it.name }}",
                    )
            }
            terminals.firstOrNull { it.isCardPresent }?.let { return it }
            val first = terminals.first()
            if (waitSeconds > 0) {
                log.info { "waiting up to ${waitSeconds}s for a card in '${first.name}'…" }
                if (!first.waitForCardPresent(waitSeconds * 1000)) {
                    throw CardException("no card inserted in '${first.name}' after ${waitSeconds}s")
                }
                return first
            }
            if (!first.isCardPresent) {
                throw CardException("no card present; insert a card, or select a reader with --reader")
            }
            return first
        }
    }
}
