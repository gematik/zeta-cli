package de.gematik.zeta.cli.popp.pace

import io.github.oshai.kotlinlogging.KotlinLogging
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.BERTags
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.math.ec.ECCurve
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

private val log = KotlinLogging.logger {}

private const val CAN_KEY_REFERENCE = 0x02
private const val AES_BLOCK_SIZE = 16
private const val MAC_BITS = 64
private const val TAG_AUTH_TOKEN = 0x49
private const val TAG_POINT = 6
private const val SW_SUCCESS = 0x9000

/** EF.CardAccess, gemSpec_eGK_ObjSys#5.3.6. */
private const val FID_CARD_ACCESS = 0x011C

/** `id-PACE` — every PACE protocol OID lives under this arc (BSI TR-03110-3). */
private const val PACE_OID_PREFIX = "0.4.0.127.0.7.2.2.4"

/** Sends one command APDU to the card and returns the full response (`data ‖ SW1 SW2`). */
internal fun interface ApduTransceiver {
    fun transmit(command: ByteArray): ByteArray
}

/**
 * Negotiate a PACE session key with a contactless eGK using its [can] (the six digits printed on
 * the card), following gemSpec_COS#14.7.2.1.1. Ported from gematik's own PACE clients
 * (`healthcard.control`'s `TrustedChannelPaceKeyExchange` and its E-Rezept Android successor).
 *
 * Protocol: read EF.CardAccess for the card's PACE parameters, MSE:Set AT to select PACE with the
 * CAN, then four GENERAL AUTHENTICATE rounds — encrypted nonce, generic mapping of the base point,
 * ephemeral key agreement, and mutual authentication over both public keys.
 */
internal fun establishPaceChannel(transceiver: ApduTransceiver, can: String): PaceKey {
    val random = SecureRandom()

    fun send(what: String, apdu: CommandApdu): ByteArray {
        val response = ResponseApdu(transceiver.transmit(apdu.bytes))
        check(response.sw == SW_SUCCESS) {
            "PACE step '$what' failed: card returned SW=%04X".format(response.sw)
        }
        return response.data
    }

    send("select root", CommandApdu.ofOptions(0x00, 0xA4, 0x04, 0x0C, null))
    send("select EF.CardAccess", CommandApdu.ofOptions(0x00, 0xA4, 0x02, 0x0C, fileIdentifier(FID_CARD_ACCESS), null))
    val paceInfo = PaceInfo(send("read EF.CardAccess", CommandApdu.ofOptions(0x00, 0xB0, 0x00, 0x00, 256)))
    log.debug { "eGK offers PACE ${paceInfo.protocolId} on ${paceInfo.curveName}" }

    send(
        "MSE:Set AT",
        CommandApdu.ofOptions(
            cla = 0x00,
            ins = 0x22,
            p1 = 0xC1,
            p2 = 0xA4,
            data = implicitTag(0, paceInfo.protocolBytes) + implicitTag(3, byteArrayOf(CAN_KEY_REFERENCE.toByte())),
            ne = null,
        ),
    )

    // Step 1 — the card's encrypted nonce, decipherable only with the CAN-derived key.
    val encryptedNonce = innerValue(send("GENERAL AUTHENTICATE (nonce)", generalAuthenticate()))
    val nonce = BigInteger(
        1,
        ByteArray(AES_BLOCK_SIZE).also {
            AESEngine.newInstance().apply {
                init(false, KeyParameter(deriveAes128Key(can.toByteArray(), KeyDerivationMode.PASSWORD)))
                processBlock(encryptedNonce, 0, it, 0)
            }
        },
    )

    // Step 2 — generic mapping: both sides contribute a key pair, and the shared point plus the
    // nonce move the curve's base point to a session-specific generator.
    val skMapping = randomScalar(paceInfo.curve, random)
    val pcdPkMapping = paceInfo.g.multiply(skMapping).getEncoded(false)
    val piccPkMapping = paceInfo.toPoint(
        innerValue(send("GENERAL AUTHENTICATE (mapping)", generalAuthenticate(pcdPkMapping, tagNo = 1))),
    )
    val mappedG = paceInfo.g.multiply(nonce).add(piccPkMapping.multiply(skMapping))

    // Step 3 — ephemeral ECDH over the mapped generator yields the session keys.
    val skEphemeral = randomScalar(paceInfo.curve, random)
    val pcdPkEphemeral = mappedG.multiply(skEphemeral).getEncoded(false)
    val piccPkEphemeral = innerValue(
        send("GENERAL AUTHENTICATE (key agreement)", generalAuthenticate(pcdPkEphemeral, tagNo = 3)),
    )
    val sharedSecret = bigIntToByteArray(
        paceInfo.toPoint(piccPkEphemeral).multiply(skEphemeral).normalize().xCoord.toBigInteger(),
    )
    val paceKey = PaceKey(
        enc = deriveAes128Key(sharedSecret, KeyDerivationMode.ENC),
        mac = deriveAes128Key(sharedSecret, KeyDerivationMode.MAC),
    )

    // Step 4 — each side MACs the other's ephemeral public key, proving both derived the same key.
    val pcdMac = authenticationToken(paceKey.mac, piccPkEphemeral, paceInfo.protocolId)
    val expectedPiccMac = authenticationToken(paceKey.mac, pcdPkEphemeral, paceInfo.protocolId)
    val piccMac = innerValue(
        send("GENERAL AUTHENTICATE (mutual auth)", generalAuthenticate(pcdMac, tagNo = 5, chaining = false)),
    )
    check(piccMac.contentEquals(expectedPiccMac)) {
        "PACE mutual authentication failed — the card's MAC does not match (wrong CAN?)"
    }
    return paceKey
}

/** The card's PACE parameters, read from EF.CardAccess (a DER `SET OF SecurityInfo`). */
internal class PaceInfo(cardAccess: ByteArray) {
    private val protocol: ASN1ObjectIdentifier
    val curveName: String

    init {
        val infos = ASN1InputStream(cardAccess).use { it.readObject() } as? ASN1Set
            ?: error("EF.CardAccess is not a SET OF SecurityInfo")
        val paceInfo = infos.objects.asSequence()
            .filterIsInstance<ASN1Sequence>()
            .firstOrNull { (it.getObjectAt(0) as? ASN1ObjectIdentifier)?.id?.startsWith(PACE_OID_PREFIX) == true }
            ?: error("EF.CardAccess carries no PACEInfo")
        protocol = paceInfo.getObjectAt(0) as ASN1ObjectIdentifier
        val parameterId = (paceInfo.getObjectAt(2) as ASN1Integer).value.toInt()
        curveName = STANDARDIZED_DOMAIN_PARAMETERS[parameterId]
            ?: error("unsupported PACE domain parameter id $parameterId")
    }

    val protocolId: String get() = protocol.id

    /** The bare OID content octets — what MSE:Set AT expects in its `80` data object. */
    val protocolBytes: ByteArray get() = protocol.encoded.let { it.copyOfRange(2, it.size) }

    private val spec = ECNamedCurveTable.getParameterSpec(curveName)

    val curve: ECCurve get() = spec.curve

    val g: ECPoint get() = spec.g

    fun toPoint(encoded: ByteArray): ECPoint {
        require(encoded.isNotEmpty() && encoded[0] == 0x04.toByte()) { "expected an uncompressed EC point" }
        val half = (encoded.size - 1) / 2
        return curve.createPoint(
            BigInteger(1, encoded.copyOfRange(1, 1 + half)),
            BigInteger(1, encoded.copyOfRange(1 + half, encoded.size)),
        )
    }

    private companion object {
        /**
         * BSI TR-03110-3 standardized domain parameters, restricted to the curves gemSpec_COS
         * allows for PACE.
         */
        val STANDARDIZED_DOMAIN_PARAMETERS = mapOf(
            13 to "BrainpoolP256r1",
            16 to "BrainpoolP384r1",
            17 to "BrainpoolP512r1",
        )
    }
}

internal enum class KeyDerivationMode(val counter: Byte) {
    ENC(1),
    MAC(2),
    PASSWORD(3),
}

/**
 * BSI TR-03110-3 key derivation for AES-128: the first 16 bytes of `SHA-1(secret ‖ counter)`,
 * where the counter selects the encryption, MAC or password key.
 */
internal fun deriveAes128Key(secret: ByteArray, mode: KeyDerivationMode): ByteArray {
    val input = secret + byteArrayOf(0, 0, 0, mode.counter)
    return MessageDigest.getInstance("SHA-1").digest(input).copyOf(16)
}

private fun randomScalar(curve: ECCurve, random: SecureRandom): BigInteger =
    BigInteger(1, ByteArray(curve.fieldSize / 8).also { random.nextBytes(it) })

/** `7C` (GENERAL AUTHENTICATE dynamic authentication data), empty for the first round. */
private fun generalAuthenticate(): CommandApdu = CommandApdu.ofOptions(
    cla = 0x10,
    ins = 0x86,
    p1 = 0x00,
    p2 = 0x00,
    data = DERTaggedObject(false, BERTags.APPLICATION, 28, DERSequence()).encoded,
    ne = EXPECTED_LENGTH_WILDCARD_SHORT,
)

private fun generalAuthenticate(data: ByteArray, tagNo: Int, chaining: Boolean = true): CommandApdu =
    CommandApdu.ofOptions(
        cla = if (chaining) 0x10 else 0x00,
        ins = 0x86,
        p1 = 0x00,
        p2 = 0x00,
        data = DERTaggedObject(
            true,
            BERTags.APPLICATION,
            28,
            DERTaggedObject(false, tagNo, DEROctetString(data)),
        ).encoded,
        ne = EXPECTED_LENGTH_WILDCARD_SHORT,
    )

/**
 * `T_PCD/T_PICC` — an AES-CMAC over the peer's ephemeral public key wrapped in the `7F49`
 * public-key data object, per TR-03110-3.
 */
private fun authenticationToken(kMac: ByteArray, publicKey: ByteArray, protocolId: String): ByteArray {
    val token = DERTaggedObject(
        false,
        BERTags.APPLICATION,
        TAG_AUTH_TOKEN,
        DERSequence(
            ASN1EncodableVector().apply {
                add(ASN1ObjectIdentifier(protocolId))
                add(DERTaggedObject(false, TAG_POINT, DEROctetString(publicKey)))
            },
        ),
    ).encoded
    val cmac = CMac(AESEngine.newInstance(), MAC_BITS).apply {
        init(KeyParameter(kMac))
        update(token, 0, token.size)
    }
    return ByteArray(cmac.macSize).also { cmac.doFinal(it, 0) }
}

private fun implicitTag(tagNo: Int, value: ByteArray): ByteArray =
    DERTaggedObject(false, tagNo, DEROctetString(value)).encoded

private fun fileIdentifier(fid: Int): ByteArray = byteArrayOf((fid shr 8).toByte(), (fid and 0xFF).toByte())

/**
 * The value of the single data object nested inside the card's `7C` response — the encrypted
 * nonce, a public key, or a MAC, depending on the round.
 */
private fun innerValue(response: ByteArray): ByteArray {
    var i = 0
    fun readLength(): Int {
        val first = response[i++].toInt() and 0xFF
        if (first <= 0x80) return first
        var length = 0
        repeat(first and 0x7F) { length = (length shl 8) or (response[i++].toInt() and 0xFF) }
        return length
    }
    require(response.size > 2 && (response[i++].toInt() and 0xFF) == 0x7C) {
        "expected a 7C dynamic authentication data object from the card"
    }
    readLength()
    i++ // the nested context-specific tag (80/82/84/86) — its number is fixed by the round
    val length = readLength()
    require(i + length <= response.size) { "truncated dynamic authentication data object" }
    return response.copyOfRange(i, i + length)
}

/** BigInteger to fixed bytes without BigInteger's sign byte. */
private fun bigIntToByteArray(value: BigInteger): ByteArray =
    value.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
