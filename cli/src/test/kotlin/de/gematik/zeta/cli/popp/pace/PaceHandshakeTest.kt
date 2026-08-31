package de.gematik.zeta.cli.popp.pace

import java.math.BigInteger
import java.security.SecureRandom
import java.util.HexFormat
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.BERTags
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.math.ec.ECPoint
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

private const val PROTOCOL_ID = "0.4.0.127.0.7.2.2.4.2.2"
private const val CAN = "123456"

/**
 * Runs the PACE negotiation against a stand-in eGK that performs the card half of
 * PACE-ECDH-GM-AES-CBC-CMAC-128. It pins the command APDUs the card sees and proves both sides
 * arrive at the same session keys; it cannot prove wire compatibility with real card firmware.
 */
class PaceHandshakeTest {
    private val hex = HexFormat.of()

    @Test
    fun `negotiates the session keys the card derived`() {
        val egk = FakeEgk()
        val paceKey = establishPaceChannel(egk, CAN)
        assertArrayEquals(egk.paceKey!!.enc, paceKey.enc)
        assertArrayEquals(egk.paceKey!!.mac, paceKey.mac)
    }

    @Test
    fun `sends the file selection and MSE Set AT the COS expects`() {
        val egk = FakeEgk()
        establishPaceChannel(egk, CAN)
        assertEquals("00a4040c", hex.formatHex(egk.commands[0]), "select root")
        assertEquals("00a4020c02011c", hex.formatHex(egk.commands[1]), "select EF.CardAccess")
        assertEquals("00b0000000", hex.formatHex(egk.commands[2]), "read EF.CardAccess")
        // '80' the PACE protocol OID, '83' the CAN as the password reference.
        assertEquals(
            "0022c1a40f800a04007f00070202040202830102",
            hex.formatHex(egk.commands[3]),
            "MSE:Set AT",
        )
        assertEquals("108600000 27c0000".replace(" ", ""), hex.formatHex(egk.commands[4]), "encrypted nonce")
        // The mapping and key-agreement rounds chain (CLA 10); mutual authentication ends it (CLA 00).
        assertEquals(0x10, egk.commands[5][0].toInt() and 0xFF)
        assertEquals(0x10, egk.commands[6][0].toInt() and 0xFF)
        assertEquals(0x00, egk.commands[7][0].toInt() and 0xFF)
    }

    @Test
    fun `a wrong CAN is rejected in the mutual authentication round`() {
        val error = assertThrows(IllegalStateException::class.java) {
            establishPaceChannel(FakeEgk(), "654321")
        }
        assertEquals(true, error.message!!.contains("SW=6300"), error.message)
    }

    @Test
    fun `a card that rejects a step fails the handshake`() {
        val egk = FakeEgk(failAt = 3)
        val error = assertThrows(IllegalStateException::class.java) { establishPaceChannel(egk, CAN) }
        assertEquals(true, error.message!!.contains("SW=6982"))
    }
}

/**
 * The PICC half of PACE-GM: it answers the file reads, then mirrors each GENERAL AUTHENTICATE
 * round with its own key pairs so the negotiated keys can be compared.
 */
private val AUTHENTICATION_FAILURE = byteArrayOf(0x63, 0x00)

private class FakeEgk(private val failAt: Int = -1) : ApduTransceiver {
    val commands = mutableListOf<ByteArray>()
    var paceKey: PaceKey? = null

    private val spec = ECNamedCurveTable.getParameterSpec("BrainpoolP256r1")
    private val random = SecureRandom()
    private val nonce = ByteArray(16).also { random.nextBytes(it) }
    private val mappingSk = randomScalar()
    private lateinit var mappedG: ECPoint
    private lateinit var ephemeralSk: BigInteger
    private lateinit var ephemeralPk: ByteArray
    private lateinit var pcdEphemeralPk: ByteArray

    override fun transmit(command: ByteArray): ByteArray {
        commands += command
        if (commands.size - 1 == failAt) return HexFormat.of().parseHex("6982")
        val apdu = CommandApdu.parse(command)
        val ins = command[1].toInt() and 0xFF
        return when {
            ins == 0xA4 -> ok()
            ins == 0xB0 -> HexFormat.of().parseHex("31143012060A04007F0007020204020202010202010D") + ok()
            ins == 0x22 -> ok()
            ins == 0x86 -> generalAuthenticate(apdu.data).let { if (it === AUTHENTICATION_FAILURE) it else it + ok() }
            else -> HexFormat.of().parseHex("6D00")
        }
    }

    private fun ok() = byteArrayOf(0x90.toByte(), 0x00)

    private fun generalAuthenticate(data: ByteArray): ByteArray {
        val value = innerValue(data)
        return when {
            value.isEmpty() -> {
                // The nonce travels encrypted under the key derived from the printed CAN.
                val key = deriveAes128Key(CAN.toByteArray(), KeyDerivationMode.PASSWORD)
                dynamicAuthenticationData(0x80, encryptBlock(key, nonce))
            }

            value.size == 65 && !::mappedG.isInitialized -> {
                val pcdMappingPk = spec.curve.decodePoint(value)
                mappedG = spec.g.multiply(BigInteger(1, nonce)).add(pcdMappingPk.multiply(mappingSk))
                ephemeralSk = randomScalar()
                dynamicAuthenticationData(0x82, spec.g.multiply(mappingSk).getEncoded(false))
            }

            value.size == 65 -> {
                pcdEphemeralPk = value
                ephemeralPk = mappedG.multiply(ephemeralSk).getEncoded(false)
                val shared = spec.curve.decodePoint(value).multiply(ephemeralSk).normalize()
                val secret = shared.xCoord.toBigInteger().toByteArray().let {
                    if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
                }
                paceKey = PaceKey(
                    enc = deriveAes128Key(secret, KeyDerivationMode.ENC),
                    mac = deriveAes128Key(secret, KeyDerivationMode.MAC),
                )
                dynamicAuthenticationData(0x84, ephemeralPk)
            }

            // The terminal MACs our ephemeral key; we answer with a MAC over its own, or reject
            // the round the way a card does when the CAN was wrong.
            value.contentEquals(authToken(ephemeralPk)) -> dynamicAuthenticationData(0x86, authToken(pcdEphemeralPk))

            else -> AUTHENTICATION_FAILURE
        }
    }

    private fun authToken(publicKey: ByteArray): ByteArray {
        val token = DERTaggedObject(
            false,
            BERTags.APPLICATION,
            0x49,
            DERSequence(
                ASN1EncodableVector().apply {
                    add(ASN1ObjectIdentifier(PROTOCOL_ID))
                    add(DERTaggedObject(false, 6, DEROctetString(publicKey)))
                },
            ),
        ).encoded
        val cmac = CMac(AESEngine.newInstance(), 64).apply {
            init(KeyParameter(paceKey!!.mac))
            update(token, 0, token.size)
        }
        return ByteArray(cmac.macSize).also { cmac.doFinal(it, 0) }
    }

    private fun randomScalar() = BigInteger(1, ByteArray(32).also { random.nextBytes(it) })

    private fun encryptBlock(key: ByteArray, block: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(block)
        }

    /** `7C { <tag> value }` — the card's half of a GENERAL AUTHENTICATE round. */
    private fun dynamicAuthenticationData(tag: Int, value: ByteArray): ByteArray =
        berTlv(0x7C, berTlv(tag, value))

    private fun berTlv(tag: Int, value: ByteArray): ByteArray = when {
        value.size < 0x80 -> byteArrayOf(tag.toByte(), value.size.toByte())
        else -> byteArrayOf(tag.toByte(), 0x81.toByte(), value.size.toByte())
    } + value

    /** The value of the single object nested in a `7C` the terminal sent us. */
    private fun innerValue(data: ByteArray): ByteArray {
        require((data[0].toInt() and 0xFF) == 0x7C) { "expected a 7C data object" }
        if (data.size <= 2) return ByteArray(0)
        val length = data[3].toInt() and 0xFF
        return data.copyOfRange(4, 4 + length)
    }
}
