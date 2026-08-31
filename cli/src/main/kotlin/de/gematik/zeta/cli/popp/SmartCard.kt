package de.gematik.zeta.cli.popp

import com.github.ajalt.clikt.core.UsageError
import de.gematik.zeta.cli.popp.pace.CommandApdu
import de.gematik.zeta.cli.popp.pace.ResponseApdu
import de.gematik.zeta.cli.popp.pace.ReaderPace
import de.gematik.zeta.cli.popp.pace.SecureMessaging
import de.gematik.zeta.cli.popp.pace.establishPaceChannel
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
private const val POLL_INTERVAL_MS = 250L

/**
 * A physically inserted or presented eGK reached over a PC/SC reader. Executes PoPP
 * Standard-scenario APDUs directly on the card via `javax.smartcardio`.
 *
 * Contact cards are addressed in the clear. A contactless eGK only answers inside a PACE channel
 * opened with the card's CAN, so [secureMessaging] is non-null there — unless the reader ran PACE
 * itself, in which case it wraps the APDUs in firmware and we again send plaintext.
 *
 * The APDU exchange mirrors the `kartos` simulator's contract: a command APDU hex in, the full
 * response APDU (`data ‖ SW`) hex out, so it drops straight into [PoppClient.runStandardScenario].
 */
internal class SmartCard private constructor(
    private val card: Card,
    private val channel: CardChannel,
    private val terminalName: String,
    private val secureMessaging: SecureMessaging?,
) : AutoCloseable {

    fun exchange(step: ScenarioStep): String {
        val commandHex = step.commandApdu.filterNot { it.isWhitespace() }
        log.debug { "card -> $commandHex" }
        val responseHex = HEX.formatHex(transmit(HEX.parseHex(commandHex)))
        log.debug { "card <- $responseHex" }

        val sw = responseHex.takeLast(4)
        if (step.expectedStatusWords.isNotEmpty() &&
            step.expectedStatusWords.none { it.equals(sw, ignoreCase = true) }
        ) {
            // The server is the authority on scenario success, so don't abort — just flag it.
            log.warn { "card returned status word $sw, expected ${step.expectedStatusWords} for $commandHex" }
        }
        return responseHex
    }

    /** Send one APDU, wrapping it in secure messaging when a software PACE channel is open. */
    private fun transmit(command: ByteArray): ByteArray {
        val onTheWire = secureMessaging?.encrypt(CommandApdu.parse(command))?.bytes ?: command
        val response = try {
            channel.transmit(CommandAPDU(onTheWire)).bytes
        } catch (e: CardException) {
            throw CardException("APDU transmit failed on '$terminalName'", e)
        }
        return secureMessaging?.decrypt(ResponseApdu(response))?.bytes ?: response
    }

    override fun close() {
        runCatching { card.disconnect(false) }
    }

    companion object {
        /**
         * Connect to a card in the first reader slot that answers, narrowed to [readerName] when
         * given (substring match), waiting up to [waitSeconds] for one to appear (0 = don't wait).
         * Every slot is probed on each pass, so a second card in reach is reported rather than
         * silently passed over; [readerOption] names the flag that pins one (`--reader` for
         * `zeta popp standard`, `--popp-reader` for `zeta serve`).
         *
         * A [ConnectionType.CONTACTLESS] card needs its [can] to open the PACE channel: the reader's
         * own PACE engine is used when it has one, else the protocol runs here.
         */
        fun connect(
            readerName: String?,
            waitSeconds: Long,
            connection: ConnectionType = ConnectionType.CONTACT,
            can: String? = null,
            readerOption: String = "--reader",
        ): SmartCard {
            val cardAccessNumber = can?.takeIf { it.isNotBlank() }
            if (connection == ConnectionType.CONTACTLESS && cardAccessNumber == null) {
                throw UsageError("a contactless eGK needs its CAN — pass --can (the digits printed on the card)")
            }

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

            val wanted = matchReaders(terminals.map { it.name }, readerName).toSet()
            val candidates = terminals.filter { it.name in wanted }
            val protocol = if (connection == ConnectionType.CONTACT) "T=1" else "*"
            val (terminal, card) = openCard(candidates, waitSeconds, protocol, readerOption)
            log.info { "using card reader: ${terminal.name}" }

            if (connection == ConnectionType.CONTACT) {
                return SmartCard(card, card.basicChannel, terminal.name, secureMessaging = null)
            }

            val channel = card.basicChannel
            val secureMessaging = if (ReaderPace.establish(card, cardAccessNumber!!)) {
                null
            } else {
                SecureMessaging(
                    establishPaceChannel({ apdu -> channel.transmit(CommandAPDU(apdu)).bytes }, cardAccessNumber),
                ).also { log.info { "PACE channel established in software" } }
            }
            return SmartCard(card, channel, terminal.name, secureMessaging)
        }

        /**
         * Connect to the first [candidates] slot that answers, retrying until a card shows up
         * within [waitSeconds] (0 = one pass). Every candidate is probed on each pass — a slot
         * without a card is skipped rather than fatal, and the cards we do not use are released
         * straight away so nothing is held from another application.
         */
        private fun openCard(
            candidates: List<CardTerminal>,
            waitSeconds: Long,
            protocol: String,
            readerOption: String,
        ): Pair<CardTerminal, Card> {
            val deadline = System.currentTimeMillis() + waitSeconds * 1000
            if (waitSeconds > 0) log.info { "waiting up to ${waitSeconds}s for a card…" }
            var probes: List<SlotProbe>
            while (true) {
                val opened = mutableListOf<Pair<CardTerminal, Card>>()
                probes = candidates.map { terminal ->
                    val (probe, card) = probeSlot(terminal, protocol)
                    if (card != null) opened += terminal to card
                    if (probe.outcome != ProbeOutcome.CARD) {
                        log.debug { "no card in '${terminal.name}': ${probe.detail}" }
                    }
                    probe
                }
                if (opened.isNotEmpty()) {
                    multipleCardsWarning(opened.map { it.first.name }, readerOption)?.let { log.warn { it } }
                    opened.drop(1).forEach { (_, card) -> runCatching { card.disconnect(false) } }
                    return opened.first()
                }
                if (System.currentTimeMillis() >= deadline) break
                Thread.sleep(POLL_INTERVAL_MS)
            }
            throw CardException(noCardMessage(probes, waitSeconds, readerOption))
        }

    }
}
