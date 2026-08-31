package de.gematik.zeta.cli.popp

import javax.smartcardio.Card
import javax.smartcardio.CardException
import javax.smartcardio.CardTerminal

/**
 * What a slot answered when we tried to connect to it. `isCardPresent` is not usable for this:
 * a dual-interface reader publishes a slot per interface, and some drivers report a phantom card
 * on the idle one — connecting is the only dependable probe.
 */
internal enum class ProbeOutcome {
    CARD,
    NO_CARD,
    HELD_ELSEWHERE,
    FAILED,
}

internal data class SlotProbe(val name: String, val outcome: ProbeOutcome, val detail: String? = null)

/**
 * Connect to [terminal] over [protocol]. The open card comes back with the probe when one answered,
 * for the caller to keep or close.
 */
internal fun probeSlot(terminal: CardTerminal, protocol: String): Pair<SlotProbe, Card?> =
    try {
        val card = terminal.connect(protocol)
        SlotProbe(terminal.name, ProbeOutcome.CARD) to card
    } catch (e: CardException) {
        val chain = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" / ")
        SlotProbe(terminal.name, classifyProbeFailure(chain), chain) to null
    }

/**
 * The JDK reports the friendly text (`"No card present"`) and wraps the raw PC/SC constant in an
 * internal `PCSCException` we can neither catch by type nor construct in a test, so classify on the
 * joined message chain.
 */
internal fun classifyProbeFailure(messageChain: String): ProbeOutcome = when {
    messageChain.contains("SCARD_E_SHARING_VIOLATION") -> ProbeOutcome.HELD_ELSEWHERE
    messageChain.contains("SCARD_E_NO_SMARTCARD") ||
        messageChain.contains("SCARD_W_REMOVED_CARD") ||
        messageChain.contains("No card present", ignoreCase = true) -> ProbeOutcome.NO_CARD

    else -> ProbeOutcome.FAILED
}

/** Narrow the reader [names] to those matching [filter] (substring, case-insensitive). */
internal fun matchReaders(names: List<String>, filter: String?): List<String> {
    if (filter == null) return names
    return names.filter { it.contains(filter, ignoreCase = true) }.ifEmpty {
        throw CardException("no reader matching '$filter'; available: ${names.joinToString()}")
    }
}

/**
 * The warning for a cardholder-ambiguous setup: we take the first slot that answered, so say which
 * one that was and what else was in reach. Null when the choice was not a choice.
 */
internal fun multipleCardsWarning(answering: List<String>, readerOption: String): String? {
    if (answering.size < 2) return null
    val others = answering.drop(1).joinToString { "'$it'" }
    return "${answering.size} readers hold a card; using '${answering.first()}'. Also answering: " +
        "$others. Pass $readerOption <substring> to pin one."
}

internal fun noCardMessage(probes: List<SlotProbe>, waitSeconds: Long, readerOption: String): String = buildString {
    append("no card could be read")
    if (waitSeconds > 0) append(" after ${waitSeconds}s")
    appendLine(". Insert the card fully, or hold it on the reader's contactless field.")
    append("Slots tried (a reader with several publishes one per interface; pick one with $readerOption):")
    probes.forEach { probe ->
        appendLine()
        append("  ${probe.name}   ${probe.describe()}")
    }
}

private fun SlotProbe.describe(): String = when (outcome) {
    ProbeOutcome.CARD -> "card present"
    ProbeOutcome.NO_CARD -> "no card"
    ProbeOutcome.HELD_ELSEWHERE -> "held by another application"
    ProbeOutcome.FAILED -> detail ?: "unavailable"
}
