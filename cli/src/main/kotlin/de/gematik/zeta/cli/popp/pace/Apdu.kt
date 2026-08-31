package de.gematik.zeta.cli.popp.pace

import java.io.ByteArrayOutputStream

/**
 * ISO/IEC 7816-4 APDU encoding, ported from gematik's E-Rezept Android client
 * (`de.gematik.ti.erp.app.card.model.command.Apdu`) so the secure-messaging wrapper below
 * sees the same case/offset semantics the eGK is known to accept.
 *
 * ```
 * case 1:  |CLA|INS|P1 |P2 |                                 len = 4
 * case 2s: |CLA|INS|P1 |P2 |LE |                             len = 5
 * case 3s: |CLA|INS|P1 |P2 |LC |...BODY...|                  len = 6..260
 * case 4s: |CLA|INS|P1 |P2 |LC |...BODY...|LE |              len = 7..261
 * case 2e: |CLA|INS|P1 |P2 |00 |LE1|LE2|                     len = 7
 * case 3e: |CLA|INS|P1 |P2 |00 |LC1|LC2|...BODY...|          len = 8..65542
 * case 4e: |CLA|INS|P1 |P2 |00 |LC1|LC2|...BODY...|LE1|LE2|  len = 10..65544
 * ```
 */
internal const val EXPECTED_LENGTH_WILDCARD_SHORT: Int = 256
internal const val EXPECTED_LENGTH_WILDCARD_EXTENDED: Int = 65536

internal class CommandApdu(
    apduBytes: ByteArray,
    val rawNc: Int,
    val rawNe: Int?,
    val dataOffset: Int,
) {
    private val apdu = apduBytes.copyOf()

    val bytes: ByteArray get() = apdu.copyOf()

    /** The command data field, empty for cases 1 and 2. */
    val data: ByteArray get() = if (rawNc == 0) ByteArray(0) else apdu.copyOfRange(dataOffset, dataOffset + rawNc)

    companion object {
        fun ofOptions(cla: Int, ins: Int, p1: Int, p2: Int, ne: Int?): CommandApdu =
            ofOptions(cla, ins, p1, p2, null, ne)

        @Suppress("CyclomaticComplexMethod")
        fun ofOptions(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray?, ne: Int?): CommandApdu {
            require(cla >= 0 && ins >= 0 && p1 >= 0 && p2 >= 0) { "APDU header fields must not be negative" }
            require(cla <= 0xFF && ins <= 0xFF && p1 <= 0xFF && p2 <= 0xFF) { "APDU header fields must not exceed 0xFF" }
            require(ne == null || ne in 0..EXPECTED_LENGTH_WILDCARD_EXTENDED) {
                "APDU response length is out of bounds [0, $EXPECTED_LENGTH_WILDCARD_EXTENDED]"
            }

            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(cla.toByte(), ins.toByte(), p1.toByte(), p2.toByte()))

            if (data == null) {
                if (ne == null) return CommandApdu(out.toByteArray(), rawNc = 0, rawNe = null, dataOffset = 0)
                if (ne <= EXPECTED_LENGTH_WILDCARD_SHORT) {
                    out.write(encodeExpectedLengthShort(ne))
                } else {
                    out.write(0x00)
                    out.write(encodeExpectedLengthExtended(ne))
                }
                return CommandApdu(out.toByteArray(), rawNc = 0, rawNe = ne, dataOffset = 0)
            }

            val nc = data.size
            require(nc <= 65535) { "APDU command data must not exceed 65535 bytes" }
            val dataOffset: Int
            if (ne == null) {
                if (nc <= 255) {
                    dataOffset = 5
                    out.write(encodeDataLengthShort(nc))
                } else {
                    dataOffset = 7
                    out.write(encodeDataLengthExtended(nc))
                }
                out.write(data)
            } else if (nc <= 255 && ne <= EXPECTED_LENGTH_WILDCARD_SHORT) {
                dataOffset = 5
                out.write(encodeDataLengthShort(nc))
                out.write(data)
                out.write(encodeExpectedLengthShort(ne))
            } else {
                dataOffset = 7
                out.write(encodeDataLengthExtended(nc))
                out.write(data)
                out.write(encodeExpectedLengthExtended(ne))
            }
            return CommandApdu(out.toByteArray(), rawNc = nc, rawNe = ne, dataOffset = dataOffset)
        }

        /**
         * Decode a wire APDU back into its case, so a command handed to us as raw hex (the PoPP
         * scenario steps) can be re-encoded inside secure messaging.
         */
        @Suppress("CyclomaticComplexMethod", "ReturnCount")
        fun parse(bytes: ByteArray): CommandApdu {
            require(bytes.size >= 4) { "APDU must be at least 4 bytes long" }
            val cla = bytes[0].toInt() and 0xFF
            val ins = bytes[1].toInt() and 0xFF
            val p1 = bytes[2].toInt() and 0xFF
            val p2 = bytes[3].toInt() and 0xFF
            fun byteAt(i: Int) = bytes[i].toInt() and 0xFF

            if (bytes.size == 4) return ofOptions(cla, ins, p1, p2, null, null)
            if (bytes.size == 5) return ofOptions(cla, ins, p1, p2, null, expectedLength(byteAt(4)))

            if (byteAt(4) != 0x00) {
                val nc = byteAt(4)
                val end = 5 + nc
                require(bytes.size == end || bytes.size == end + 1) { "malformed short APDU: ${bytes.size} bytes" }
                val body = bytes.copyOfRange(5, end)
                val ne = if (bytes.size == end + 1) expectedLength(byteAt(end)) else null
                return ofOptions(cla, ins, p1, p2, body, ne)
            }

            // Lc == 0x00 marks the extended form: either 3e/4e (Lc1 Lc2 non-zero) or 2e (Le1 Le2 only).
            require(bytes.size >= 7) { "malformed extended APDU: ${bytes.size} bytes" }
            val extLen = (byteAt(5) shl 8) or byteAt(6)
            if (bytes.size == 7) return ofOptions(cla, ins, p1, p2, null, extendedExpectedLength(extLen))
            val end = 7 + extLen
            require(bytes.size == end || bytes.size == end + 2) { "malformed extended APDU: ${bytes.size} bytes" }
            val body = bytes.copyOfRange(7, end)
            val ne = if (bytes.size == end + 2) {
                extendedExpectedLength((byteAt(end) shl 8) or byteAt(end + 1))
            } else {
                null
            }
            return ofOptions(cla, ins, p1, p2, body, ne)
        }

        private fun expectedLength(le: Int) = if (le == 0) EXPECTED_LENGTH_WILDCARD_SHORT else le

        private fun extendedExpectedLength(le: Int) = if (le == 0) EXPECTED_LENGTH_WILDCARD_EXTENDED else le

        private fun encodeDataLengthShort(nc: Int) = byteArrayOf(nc.toByte())

        private fun encodeDataLengthExtended(nc: Int) =
            byteArrayOf(0x00, (nc shr 8).toByte(), (nc and 0xFF).toByte())

        private fun encodeExpectedLengthShort(ne: Int) =
            byteArrayOf(if (ne != EXPECTED_LENGTH_WILDCARD_EXTENDED) ne.toByte() else 0x00)

        private fun encodeExpectedLengthExtended(ne: Int) =
            if (ne != EXPECTED_LENGTH_WILDCARD_EXTENDED) {
                byteArrayOf((ne shr 8).toByte(), (ne and 0xFF).toByte())
            } else {
                byteArrayOf(0x00, 0x00)
            }
    }
}

internal class ResponseApdu(apdu: ByteArray) {
    init {
        require(apdu.size >= 2) { "response APDU must carry at least the status bytes SW1 SW2" }
    }

    private val apdu = apdu.copyOf()

    val data: ByteArray get() = apdu.copyOfRange(0, apdu.size - 2)

    val sw: Int get() = ((apdu[apdu.size - 2].toInt() and 0xFF) shl 8) or (apdu[apdu.size - 1].toInt() and 0xFF)

    val bytes: ByteArray get() = apdu.copyOf()
}

/** ISO 7816-4 padding (method 2): append `0x80`, then zeroes up to the next [blockSize] boundary. */
internal fun padData(data: ByteArray, blockSize: Int): ByteArray =
    ByteArray(data.size + (blockSize - data.size % blockSize)).apply {
        data.copyInto(this)
        this[data.size] = 0x80.toByte()
    }

internal fun unPadData(paddedData: ByteArray): ByteArray {
    for (i in paddedData.indices.reversed()) {
        if (paddedData[i] == 0x80.toByte()) return paddedData.copyOfRange(0, i)
    }
    return paddedData
}
