package de.gematik.zeta.cli.popp.pace

import java.util.HexFormat
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * PoPP hands us scenario steps as raw APDU hex, so every ISO 7816-4 case has to survive a
 * parse before secure messaging can re-encode it.
 */
class ApduTest {
    private val hex = HexFormat.of()

    private fun assertRoundTrip(apduHex: String, nc: Int, ne: Int?) {
        val bytes = hex.parseHex(apduHex)
        val parsed = CommandApdu.parse(bytes)
        assertArrayEquals(bytes, parsed.bytes, "re-encoded APDU differs for $apduHex")
        assertEquals(nc, parsed.rawNc)
        assertEquals(ne, parsed.rawNe)
    }

    @Test
    fun `case 1 - header only`() = assertRoundTrip("00A4040C", nc = 0, ne = null)

    @Test
    fun `case 2s - short expected length`() = assertRoundTrip("00B0000040", nc = 0, ne = 0x40)

    @Test
    fun `case 2s - Le zero means 256`() =
        assertRoundTrip("00B0000000", nc = 0, ne = EXPECTED_LENGTH_WILDCARD_SHORT)

    @Test
    fun `case 3s - command data`() = assertRoundTrip("00A4020C020 11C".replace(" ", ""), nc = 2, ne = null)

    @Test
    fun `case 4s - command data and expected length`() =
        assertRoundTrip("00220081020A0C00", nc = 2, ne = EXPECTED_LENGTH_WILDCARD_SHORT)

    @Test
    fun `case 2e - extended expected length`() = assertRoundTrip("00B00000000101", nc = 0, ne = 0x101)

    @Test
    fun `case 2e - extended Le zero means 65536`() =
        assertRoundTrip("00B0000000 0000".replace(" ", ""), nc = 0, ne = EXPECTED_LENGTH_WILDCARD_EXTENDED)

    @Test
    fun `case 3e - extended command data`() {
        val data = ByteArray(300) { it.toByte() }
        val apdu = CommandApdu.ofOptions(0x00, 0xD6, 0x00, 0x00, data, null)
        val parsed = CommandApdu.parse(apdu.bytes)
        assertArrayEquals(data, parsed.data)
        assertNull(parsed.rawNe)
    }

    @Test
    fun `case 4e - extended command data and expected length`() {
        val data = ByteArray(300) { it.toByte() }
        val apdu = CommandApdu.ofOptions(0x00, 0xD6, 0x00, 0x00, data, 512)
        val parsed = CommandApdu.parse(apdu.bytes)
        assertArrayEquals(data, parsed.data)
        assertEquals(512, parsed.rawNe)
    }

    @Test
    fun `a truncated APDU is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { CommandApdu.parse(hex.parseHex("00A402")) }
        assertThrows(IllegalArgumentException::class.java) { CommandApdu.parse(hex.parseHex("00A4020C05011C")) }
    }

    @Test
    fun `ISO 7816-4 padding round-trips`() {
        val data = byteArrayOf(1, 2, 3)
        assertArrayEquals(hex.parseHex("01020380000000000000000000000000"), padData(data, 16))
        assertArrayEquals(data, unPadData(padData(data, 16)))
        // A full block still gains a whole block of padding, so the 0x80 is unambiguous.
        val block = ByteArray(16) { 0x11 }
        assertEquals(32, padData(block, 16).size)
        assertArrayEquals(block, unPadData(padData(block, 16)))
    }
}
