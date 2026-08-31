package de.gematik.zeta.cli.popp

import javax.smartcardio.CardException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val CONTACT_SLOT = "Identive Identive CLOUD 4700 F Dual Interface Reader"
private const val CONTACTLESS_SLOT = "Identive Identive CLOUD 4700 F Dual Interface Reader 01"

class CardSlotsTest {

    // ───── matchReaders ────────────────────────────────────────────────────

    @Test
    fun `no filter keeps every slot`() {
        val names = listOf(CONTACT_SLOT, CONTACTLESS_SLOT)
        assertEquals(names, matchReaders(names, null))
    }

    @Test
    fun `filter matches a substring case-insensitively`() {
        assertEquals(listOf(CONTACTLESS_SLOT), matchReaders(listOf(CONTACT_SLOT, CONTACTLESS_SLOT), "reader 01"))
    }

    @Test
    fun `a filter matching several slots keeps them all`() {
        val names = listOf(CONTACT_SLOT, CONTACTLESS_SLOT)
        assertEquals(names, matchReaders(names, "Identive"))
    }

    @Test
    fun `a filter matching nothing names what is available`() {
        val error = assertThrows(CardException::class.java) {
            matchReaders(listOf(CONTACT_SLOT, CONTACTLESS_SLOT), "Cherry")
        }
        assertTrue("no reader matching 'Cherry'" in error.message!!, error.message)
        assertTrue(CONTACT_SLOT in error.message!!, error.message)
    }

    // ───── classifyProbeFailure ────────────────────────────────────────────

    @Test
    fun `the JDK's friendly wording counts as an empty slot`() {
        assertEquals(ProbeOutcome.NO_CARD, classifyProbeFailure("No card present"))
    }

    @Test
    fun `the raw PC-SC constants count as an empty slot`() {
        assertEquals(ProbeOutcome.NO_CARD, classifyProbeFailure("No card present / SCARD_E_NO_SMARTCARD"))
        assertEquals(ProbeOutcome.NO_CARD, classifyProbeFailure("SCARD_W_REMOVED_CARD"))
    }

    @Test
    fun `a card held by another application is distinguished`() {
        assertEquals(
            ProbeOutcome.HELD_ELSEWHERE,
            classifyProbeFailure("connect() failed / SCARD_E_SHARING_VIOLATION"),
        )
    }

    @Test
    fun `anything else is a failure worth reporting`() {
        assertEquals(ProbeOutcome.FAILED, classifyProbeFailure("SCARD_E_PROTO_MISMATCH"))
    }

    // ───── multipleCardsWarning ────────────────────────────────────────────

    @Test
    fun `no warning when the choice was not a choice`() {
        assertNull(multipleCardsWarning(emptyList(), "--reader"))
        assertNull(multipleCardsWarning(listOf(CONTACTLESS_SLOT), "--reader"))
    }

    @Test
    fun `several cards name the one used, the rest, and the option that pins it`() {
        val warning = multipleCardsWarning(listOf(CONTACTLESS_SLOT, CONTACT_SLOT), "--reader")!!
        assertTrue(warning.startsWith("2 readers hold a card; using '$CONTACTLESS_SLOT'."), warning)
        assertTrue("Also answering: '$CONTACT_SLOT'" in warning, warning)
        assertTrue("--reader <substring>" in warning, warning)
    }

    @Test
    fun `the warning names the option spelling the caller uses`() {
        val warning = multipleCardsWarning(listOf(CONTACTLESS_SLOT, CONTACT_SLOT), "--popp-reader")!!
        assertTrue("--popp-reader <substring>" in warning, warning)
        assertTrue("--reader <" !in warning, "serve must not be told to pass --reader: $warning")
    }

    // ───── noCardMessage ───────────────────────────────────────────────────

    @Test
    fun `the no-card message lists every slot probed`() {
        val message = noCardMessage(
            listOf(
                SlotProbe(CONTACT_SLOT, ProbeOutcome.NO_CARD),
                SlotProbe(CONTACTLESS_SLOT, ProbeOutcome.NO_CARD),
            ),
            waitSeconds = 0,
            readerOption = "--reader",
        )
        assertTrue(message.startsWith("no card could be read."), message)
        assertTrue(CONTACT_SLOT in message && CONTACTLESS_SLOT in message, message)
        assertTrue("pick one with --reader" in message, message)
    }

    @Test
    fun `waiting is reflected in the no-card message`() {
        val message = noCardMessage(
            listOf(SlotProbe(CONTACT_SLOT, ProbeOutcome.NO_CARD)),
            waitSeconds = 10,
            readerOption = "--reader",
        )
        assertTrue("no card could be read after 10s." in message, message)
    }

    @Test
    fun `a card held elsewhere is called out rather than reported as absent`() {
        val message = noCardMessage(
            listOf(SlotProbe(CONTACT_SLOT, ProbeOutcome.HELD_ELSEWHERE, "SCARD_E_SHARING_VIOLATION")),
            waitSeconds = 0,
            readerOption = "--reader",
        )
        assertTrue("held by another application" in message, message)
    }

    @Test
    fun `an unclassified failure keeps its detail`() {
        val message = noCardMessage(
            listOf(SlotProbe(CONTACT_SLOT, ProbeOutcome.FAILED, "SCARD_E_PROTO_MISMATCH")),
            waitSeconds = 0,
            readerOption = "--reader",
        )
        assertTrue("SCARD_E_PROTO_MISMATCH" in message, message)
    }
}
