package de.gematik.zeta.cli.popp.pace

import java.util.HexFormat
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The expected byte strings are gematik's own secure-messaging test vectors, taken from the
 * E-Rezept Android client's `SecureMessagingTest` — they pin the SSC handling, the DO layout and
 * the CMAC input, which is the part of the channel a real card silently rejects when it is wrong.
 */
class SecureMessagingTest {
    private val hex = HexFormat.of()
    private val paceKey = PaceKey(
        enc = hex.parseHex("68406B4162100563D9C901A6154D2901"),
        mac = hex.parseHex("73FF268784F72AF833FDC9464049AFC9"),
    )

    private fun secureMessaging() = SecureMessaging(paceKey)

    @Test
    fun `case 1 - header only`() {
        val encrypted = secureMessaging().encrypt(CommandApdu.ofOptions(0x01, 0x02, 0x03, 0x04, null))
        assertArrayEquals(hex.parseHex("0D0203040A8E08D92B4FDDC2BBED8C00"), encrypted.bytes)
    }

    @Test
    fun `case 2s - short expected length`() {
        val encrypted = secureMessaging().encrypt(CommandApdu.ofOptions(0x01, 0x02, 0x03, 0x04, 127))
        assertArrayEquals(hex.parseHex("0D02030400000D97017F8E0871D8E0418DAE20F30000"), encrypted.bytes)
    }

    @Test
    fun `case 2e - extended expected length`() {
        val encrypted = secureMessaging().encrypt(CommandApdu.ofOptions(0x01, 0x02, 0x03, 0x04, 257))
        assertArrayEquals(hex.parseHex("0D02030400000E970201018E089F3EDDFBB1D3971D0000"), encrypted.bytes)
    }

    @Test
    fun `case 3s - command data, no expected length`() {
        val data = byteArrayOf(0x05, 0x06, 0x07, 0x08, 0x09, 0x0a)
        val encrypted = secureMessaging().encrypt(CommandApdu.ofOptions(0x01, 0x02, 0x03, 0x04, data, null))
        assertArrayEquals(
            hex.parseHex("0D0203041D871101496C26D36306679609665A385C54DB378E08E7AAD918F260D8EF00"),
            encrypted.bytes,
        )
    }

    @Test
    fun `case 4s - command data and expected length`() {
        val data = byteArrayOf(0x05, 0x06, 0x07, 0x08, 0x09, 0x0a)
        val encrypted = secureMessaging().encrypt(CommandApdu.ofOptions(0x01, 0x02, 0x03, 0x04, data, 127))
        assertArrayEquals(
            hex.parseHex("0D020304000020871101496C26D36306679609665A385C54DB3797017F8E0863D541F262BD445A0000"),
            encrypted.bytes,
        )
    }

    @Test
    fun `case 4e - extended command data`() {
        val encrypted = secureMessaging().encrypt(CommandApdu.ofOptions(0x01, 0x02, 0x03, 0x04, ByteArray(256), 127))
        assertArrayEquals(
            hex.parseHex(
                "0D02030400012287820111013297D4AA774AB26AF8AD539C0A829BCA4D222D3EE2DB100CF86D7DB5A1FAC12B7623328DEFE3F6" +
                    "FDD41A993AC917BC17B364C3DD24740079DE60A3D0231A7185D36A77D37E147025913ADA00CD07736CFDE0DB2E0BB09B75" +
                    "C5773607E54A9D84181ACBC6F7726762A8BCE324C0B330548114154A13EDDBFF6DCBC3773DCA9A8494404BE4A5654273F9" +
                    "C2B9EBE1BD615CB39FFD0D3F2A0EEA29AA10B810D53EDB550FB741A68CC6B0BDF928F9EB6BC238416AACB4CF3002E865D4" +
                    "86CF42D762C86EEBE6A2B25DECE2E88D569854A07D3F146BC134BAF08B6EDCBEBDFF47EBA6AC7B441A1642B03253B588C4" +
                    "9B69ABBEC92BA1723B7260DE8AD6158873141AFA7C70CFCF125BA1DF77CA48025D049FCEE497017F8E0856332C83EABDF9" +
                    "3C0000",
            ),
            encrypted.bytes,
        )
    }

    @Test
    fun `encrypting an already wrapped APDU is refused`() {
        val sm = secureMessaging()
        val encrypted = sm.encrypt(CommandApdu.ofOptions(0x01, 0x02, 0x03, 0x04, null))
        assertThrows(IllegalArgumentException::class.java) { sm.encrypt(encrypted) }
    }

    @Test
    fun `status-only response`() {
        val decrypted = secureMessaging().decrypt(ResponseApdu(hex.parseHex("990290008E08087631D746F872729000")))
        assertArrayEquals(hex.parseHex("9000"), decrypted.bytes)
    }

    @Test
    fun `response with encrypted data`() {
        val decrypted = secureMessaging().decrypt(
            ResponseApdu(hex.parseHex("871101496c26d36306679609665a385c54db37990290008E08B7E9ED2A0C89FB3A9000")),
        )
        assertArrayEquals(hex.parseHex("05060708090a9000"), decrypted.bytes)
    }

    @Test
    fun `response without a status data object is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            secureMessaging().decrypt(
                ResponseApdu(hex.parseHex("871101496c26d36306679609665a385c54db378E08B7E9ED2A0C89FB3A9000")),
            )
        }
    }

    @Test
    fun `response without a MAC is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            secureMessaging().decrypt(ResponseApdu(hex.parseHex("871101496c26d36306679609665a385c54db37990290009000")))
        }
    }

    @Test
    fun `response with a wrong MAC is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            secureMessaging().decrypt(
                ResponseApdu(hex.parseHex("871101496c26d36306679609665a385c54db37990290008E08A7E9ED2A0C89FB3A9000")),
            )
        }
    }

    @Test
    fun `an unwrapped response is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            secureMessaging().decrypt(ResponseApdu(hex.parseHex("9000")))
        }
    }
}
