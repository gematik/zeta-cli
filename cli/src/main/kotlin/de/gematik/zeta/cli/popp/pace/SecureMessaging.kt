package de.gematik.zeta.cli.popp.pace

import org.bouncycastle.asn1.ASN1Object
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.params.KeyParameter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.or

private const val SECURE_MESSAGING_CLA_BIT = 0x0C.toByte()
private val PADDING_INDICATOR = byteArrayOf(0x01)
private const val BLOCK_SIZE = 16
private const val MAC_SIZE = 8
private const val MAC_BITS = 64
private const val HEADER_SIZE = 4
private const val MIN_RESPONSE_SIZE = 12

// Context-specific tag numbers; the encoder ORs in 0x80, giving DO'81'/'87'/'97'/'99'/'8E'.
private const val DO_81 = 0x01
private const val DO_87 = 0x07
private const val DO_97 = 0x17
private const val DO_99 = 0x19
private const val DO_8E = 0x0E

private const val MALFORMED = "malformed Secure Messaging APDU"

/** The AES-128 session keys negotiated by PACE. */
internal data class PaceKey(val enc: ByteArray, val mac: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PaceKey && enc.contentEquals(other.enc) && mac.contentEquals(other.mac))

    override fun hashCode(): Int = 31 * enc.contentHashCode() + mac.contentHashCode()
}

/**
 * ISO 7816-4 / gemSpec_COS secure messaging over a PACE channel: wraps each command APDU into
 * `DO'87' ‖ DO'97' ‖ DO'8E'` under [PaceKey.enc]/[PaceKey.mac] and unwraps the card's response.
 * Ported from gematik's E-Rezept Android client (`card.model.card.SecureMessaging`), whose test
 * vectors this implementation is checked against.
 *
 * Stateful: the send-sequence counter advances once per command and once per response, so one
 * instance belongs to exactly one card session and its calls must stay in order.
 */
internal class SecureMessaging(private val paceKey: PaceKey) {
    private val ssc = ByteArray(BLOCK_SIZE)

    private fun incrementSsc() {
        for (i in ssc.indices.reversed()) {
            ssc[i]++
            if (ssc[i] != 0.toByte()) break
        }
    }

    fun encrypt(command: CommandApdu): CommandApdu {
        val plain = command.bytes
        require(plain.size >= HEADER_SIZE) { "APDU must be at least $HEADER_SIZE bytes long" }
        incrementSsc()

        val header = plain.copyOfRange(0, HEADER_SIZE)
        require(header[0] != (header[0] or SECURE_MESSAGING_CLA_BIT)) { "APDU is already secure-messaged" }
        header[0] = header[0] or SECURE_MESSAGING_CLA_BIT

        val body = ByteArrayOutputStream()
        command.data.takeIf { it.isNotEmpty() }?.let { data ->
            val encrypted = PADDING_INDICATOR + cipher(Cipher.ENCRYPT_MODE).doFinal(padData(data, BLOCK_SIZE))
            taggedObject(DO_87, encrypted).encodeTo(body)
        }
        command.rawNe?.let { taggedObject(DO_97, encodeLe(it)).encodeTo(body) }

        val mac = computeMac(header, body.toByteArray())
        val smData = body.also { taggedObject(DO_8E, mac).encodeTo(it) }.toByteArray()

        // The card answers with at least DO'99' ‖ DO'8E', so an Le is always required; a plain
        // command that asked for a response, or one whose wrapped body no longer fits a short
        // length, must use the extended form.
        val ne = if (command.rawNe == null && smData.size <= 255) {
            EXPECTED_LENGTH_WILDCARD_SHORT
        } else {
            EXPECTED_LENGTH_WILDCARD_EXTENDED
        }
        return CommandApdu.ofOptions(
            cla = header[0].toInt() and 0xFF,
            ins = header[1].toInt() and 0xFF,
            p1 = header[2].toInt() and 0xFF,
            p2 = header[3].toInt() and 0xFF,
            data = smData,
            ne = ne,
        )
    }

    fun decrypt(response: ResponseApdu): ResponseApdu {
        val bytes = response.bytes
        require(bytes.size >= MIN_RESPONSE_SIZE) { MALFORMED }
        incrementSsc()

        val input = ByteArrayInputStream(bytes)
        var tag = input.read()
        var dataTag = 0
        var data: ByteArray? = null
        if (tag == 0x81 || tag == 0x87) {
            dataTag = tag
            data = ByteArray(readLength(input)).also { input.readFully(it) }
            tag = input.read()
        }
        require(tag == 0x99) { MALFORMED }
        require(input.read() == 2) { MALFORMED }
        val status = ByteArray(2).also { input.readFully(it) }
        require(input.read() == 0x8E) { MALFORMED }
        require(input.read() == MAC_SIZE) { MALFORMED }
        val mac = ByteArray(MAC_SIZE).also { input.readFully(it) }
        require(input.available() == 2) { MALFORMED }

        val macInput = ByteArrayOutputStream().apply {
            data?.let { taggedObject(if (dataTag == 0x81) DO_81 else DO_87, it).encodeTo(this) }
            taggedObject(DO_99, status).encodeTo(this)
        }
        require(computeMac(header = null, body = macInput.toByteArray()).contentEquals(mac)) {
            "Secure Messaging MAC verification failed"
        }

        val plain = ByteArrayOutputStream()
        if (data != null) {
            if (dataTag == 0x87) {
                plain.write(unPadData(cipher(Cipher.DECRYPT_MODE).doFinal(data.copyOfRange(1, data.size))))
            } else {
                plain.write(data)
            }
        }
        plain.write(status)
        return ResponseApdu(plain.toByteArray())
    }

    private fun computeMac(header: ByteArray?, body: ByteArray): ByteArray {
        val cmac = CMac(AESEngine.newInstance(), MAC_BITS).apply {
            init(KeyParameter(paceKey.mac))
            update(ssc, 0, ssc.size)
        }
        header?.let { padData(it, BLOCK_SIZE).let { p -> cmac.update(p, 0, p.size) } }
        if (body.isNotEmpty()) padData(body, BLOCK_SIZE).let { cmac.update(it, 0, it.size) }
        return ByteArray(cmac.macSize).also { cmac.doFinal(it, 0) }
    }

    private fun cipher(mode: Int): Cipher =
        Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(mode, SecretKeySpec(paceKey.enc, "AES"), IvParameterSpec(sscIv()))
        }

    // ECB rather than CBC on purpose: the COS derives the CBC IV by encrypting the SSC.
    private fun sscIv(): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(paceKey.enc, "AES"))
            doFinal(ssc)
        }
}

private fun taggedObject(tagNo: Int, value: ByteArray): ASN1Object =
    DERTaggedObject(false, tagNo, DEROctetString(value))

private fun encodeLe(ne: Int): ByteArray = when {
    ne == EXPECTED_LENGTH_WILDCARD_SHORT -> byteArrayOf(0x00)
    ne > EXPECTED_LENGTH_WILDCARD_SHORT -> byteArrayOf((ne shr 8).toByte(), (ne and 0xFF).toByte())
    else -> byteArrayOf(ne.toByte())
}

private fun readLength(input: InputStream): Int {
    val first = input.read()
    require(first >= 0) { MALFORMED }
    if (first <= 0x80) return first
    var length = 0
    repeat(first and 0x7F) {
        val b = input.read()
        require(b >= 0) { MALFORMED }
        length = (length shl 8) or b
    }
    return length
}

private fun InputStream.readFully(buffer: ByteArray) {
    require(read(buffer, 0, buffer.size) == buffer.size) { MALFORMED }
}
