package de.gematik.zeta.cli.popp.pace

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.ByteArrayOutputStream
import javax.smartcardio.Card
import javax.smartcardio.CardException

private val log = KotlinLogging.logger {}

/** PC/SC part 10 `CM_IOCTL_GET_FEATURE_REQUEST`. */
private const val GET_FEATURE_REQUEST = 3400

/** `FEATURE_EXECUTE_PACE`, the tag a class-3 reader uses to advertise its own PACE engine. */
private const val FEATURE_EXECUTE_PACE = 0x20

private const val FUNCTION_GET_CAPABILITIES = 0x01
private const val FUNCTION_ESTABLISH_CHANNEL = 0x02

/** BSI TR-03119 password reference for the card access number. */
private const val PASSWORD_ID_CAN = 0x02

/**
 * PACE performed by the card reader itself (PC/SC part 10 / BSI TR-03119 `EstablishPACEChannel`).
 * Class-3 "comfort" readers run the protocol in firmware and keep the secure-messaging channel
 * inside the reader, so the CAN never reaches the host and APDUs stay plaintext on our side.
 *
 * Readers without the feature — most plain contactless readers — leave PACE to [establishPaceChannel].
 */
internal object ReaderPace {

    /**
     * Ask [card]'s reader to open a PACE channel with [can]. Returns false when the reader has no
     * PACE engine, so the caller can fall back to software PACE; throws when the reader has one and
     * it rejects the card or the CAN.
     */
    fun establish(card: Card, can: String): Boolean {
        val controlCode = executePaceControlCode(card) ?: return false
        logCapabilities(card, controlCode)

        val response = card.transmitControlCommand(controlCode, establishChannelCommand(can))
        require(response.size >= 6) { "reader returned a truncated EstablishPACEChannel response" }
        val result = readUInt32Le(response, 0)
        if (result != 0L) {
            throw CardException("reader-driven PACE failed with result 0x%08X (wrong CAN?)".format(result))
        }
        log.info { "PACE channel established by the reader" }
        return true
    }

    /** Whether [card]'s reader runs PACE in firmware — surfaced by `zeta popp readers`. */
    fun advertisesPace(card: Card): Boolean = executePaceControlCode(card) != null

    /** The vendor control code for `EstablishPACEChannel`, or null when the reader has no PACE engine. */
    private fun executePaceControlCode(card: Card): Int? {
        val features = try {
            card.transmitControlCommand(controlCode(GET_FEATURE_REQUEST), ByteArray(0))
        } catch (e: CardException) {
            log.debug { "reader does not answer GET_FEATURE_REQUEST (${e.message}); using software PACE" }
            return null
        }
        // The feature list is a flat run of {tag, length, 4-byte big-endian control code} entries.
        var i = 0
        while (i + 2 <= features.size) {
            val tag = features[i].toInt() and 0xFF
            val length = features[i + 1].toInt() and 0xFF
            if (i + 2 + length > features.size) break
            if (tag == FEATURE_EXECUTE_PACE && length == 4) {
                return readUInt32Be(features, i + 2)
            }
            i += 2 + length
        }
        log.debug { "reader advertises no FEATURE_EXECUTE_PACE; using software PACE" }
        return null
    }

    private fun logCapabilities(card: Card, controlCode: Int) {
        runCatching {
            card.transmitControlCommand(controlCode, byteArrayOf(FUNCTION_GET_CAPABILITIES.toByte(), 0x00, 0x00))
        }.onSuccess { log.debug { "reader PACE capabilities: ${it.toHex()}" } }
    }

    /**
     * ```
     * idxFunction | lengthInputData (LE) | passwordId | lengthCHAT | lengthPIN | PIN | lengthCertDesc (LE)
     * ```
     */
    private fun establishChannelCommand(can: String): ByteArray {
        val pin = can.toByteArray(Charsets.US_ASCII)
        val input = ByteArrayOutputStream().apply {
            write(PASSWORD_ID_CAN)
            write(0x00) // no CHAT — a CAN-only channel, not an eID authentication
            write(pin.size)
            write(pin)
            write(byteArrayOf(0x00, 0x00)) // no certificate description
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write(FUNCTION_ESTABLISH_CHANNEL)
            write(byteArrayOf((input.size and 0xFF).toByte(), ((input.size shr 8) and 0xFF).toByte()))
            write(input)
        }.toByteArray()
    }

    /**
     * PC/SC control codes are built differently per platform: Windows uses the
     * `FILE_DEVICE_SMARTCARD` device-IO scheme, PCSC-lite (macOS, Linux) a flat vendor range.
     */
    private fun controlCode(code: Int): Int =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            (0x31 shl 16) or (code shl 2)
        } else {
            0x42000000 + code
        }

    private fun readUInt32Le(bytes: ByteArray, offset: Int): Long =
        (0..3).fold(0L) { acc, i -> acc or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i)) }

    private fun readUInt32Be(bytes: ByteArray, offset: Int): Int =
        (0..3).fold(0) { acc, i -> (acc shl 8) or (bytes[offset + i].toInt() and 0xFF) }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
