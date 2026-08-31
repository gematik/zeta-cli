package de.gematik.zeta.cli.popp

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.mordant.rendering.TextStyles
import de.gematik.zeta.cli.ZetaCliktCommand
import de.gematik.zeta.cli.output.OutputFormat
import de.gematik.zeta.cli.output.renderJson
import de.gematik.zeta.cli.popp.pace.ReaderPace
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.HexFormat
import javax.smartcardio.Card
import javax.smartcardio.CardException
import javax.smartcardio.CardTerminal
import javax.smartcardio.TerminalFactory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

private val log = KotlinLogging.logger {}
private val HEX = HexFormat.of()

/**
 * `zeta popp readers` — list the PC/SC reader slots visible to this machine. Useful for picking the
 * `--reader` value for `zeta popp standard` (or `--popp-reader` for `zeta serve`), and for seeing
 * whether a reader runs PACE itself before you present a contactless eGK.
 *
 * Each slot is probed by connecting, never by `isCardPresent`: a dual-interface reader publishes a
 * slot per interface and some drivers report a phantom card on the idle one.
 */
class PoppReadersCommand : ZetaCliktCommand(name = "readers") {
    override fun help(context: Context) =
        "List local PC/SC card reader slots, the cards in them, and their PACE support."

    override fun runCommand() {
        val terminals = try {
            TerminalFactory.getDefault().terminals().list()
        } catch (e: CardException) {
            throw CardException(
                "no PC/SC smartcard service available — is a reader connected" +
                    " (and pcscd running on Linux)?",
                e,
            )
        }
        log.debug { "PC/SC reported ${terminals.size} slot(s)" }
        val slots = terminals.map { inspect(it) }
        when (cliConfig.outputFormat) {
            OutputFormat.JSON -> echo(renderJson(jsonReport(slots), colorize = colorize))
            OutputFormat.TEXT, OutputFormat.RAW -> echo(textReport(slots))
        }
    }
}

/**
 * One reader slot as this command reports it. [atr] is present only when a card answered; [pace] is
 * null when the reader would not say (the capability query needs a control connection, which not
 * every driver grants).
 */
private data class SlotReport(val name: String, val probe: SlotProbe, val atr: String?, val pace: Boolean?)

private fun inspect(terminal: CardTerminal): SlotReport {
    val (probe, card) = probeSlot(terminal, "*")
    return try {
        SlotReport(
            name = terminal.name,
            probe = probe,
            atr = card?.let { HEX.formatHex(it.atr.bytes) },
            pace = paceSupport(terminal, card),
        )
    } finally {
        card?.let { runCatching { it.disconnect(false) } }
    }
}

/**
 * Ask the reader whether it advertises `FEATURE_EXECUTE_PACE`. With no card in the slot this needs a
 * `direct` connection, which some drivers refuse — then we simply don't know.
 */
private fun paceSupport(terminal: CardTerminal, card: Card?): Boolean? {
    card?.let { return runCatching { ReaderPace.advertisesPace(it) }.getOrNull() }
    return runCatching {
        val direct = terminal.connect("direct")
        try {
            ReaderPace.advertisesPace(direct)
        } finally {
            direct.disconnect(false)
        }
    }.getOrNull()
}

private fun SlotProbe.describe(): String = when (outcome) {
    ProbeOutcome.CARD -> "yes"
    ProbeOutcome.NO_CARD -> "no"
    ProbeOutcome.HELD_ELSEWHERE -> "in use"
    ProbeOutcome.FAILED -> "error"
}

private fun jsonReport(slots: List<SlotReport>): JsonArray = buildJsonArray {
    slots.forEach { s ->
        addJsonObject {
            put("reader", s.name)
            put("card", s.probe.outcome.name.lowercase())
            put("atr", s.atr?.let(::JsonPrimitive) ?: JsonNull)
            put("pace", s.pace?.let(::JsonPrimitive) ?: JsonNull)
            put("detail", s.probe.detail?.let(::JsonPrimitive) ?: JsonNull)
        }
    }
}

private fun textReport(slots: List<SlotReport>): String {
    if (slots.isEmpty()) return "No card readers found."

    val nameW = maxOf("READER".length, slots.maxOf { it.name.length })
    val cardW = maxOf("CARD".length, slots.maxOf { it.probe.describe().length })
    val paceW = maxOf("PACE".length, slots.maxOf { paceText(it.pace).length })
    val header = TextStyles.bold(
        "${"READER".padEnd(nameW)}  ${"CARD".padEnd(cardW)}  ${"PACE".padEnd(paceW)}  ATR",
    )
    return buildString {
        appendLine(header)
        slots.forEach { s ->
            appendLine(
                "${s.name.padEnd(nameW)}  " +
                    "${s.probe.describe().padEnd(cardW)}  " +
                    "${paceText(s.pace).padEnd(paceW)}  " +
                    (s.atr ?: "-"),
            )
        }
    }.trimEnd()
}

private fun paceText(pace: Boolean?): String = when (pace) {
    true -> "reader"
    false -> "software"
    null -> "-"
}
