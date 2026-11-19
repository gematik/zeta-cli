/*
 * Copyright (Change Date see Readme), gematik GmbH
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the
 * European Commission – subsequent versions of the EUPL (the "Licence").
 * You may not use this work except in compliance with the Licence.
 *
 * You find a copy of the Licence in the "Licence" file or at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.
 * In case of changes by gematik GmbH find details in the "Readme" file.
 *
 * See the Licence for the specific language governing permissions and limitations under the Licence.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 */
package de.gematik.zeta.cli.output

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigInteger
import kotlin.io.encoding.Base64

/**
 * Decode one CBOR item (RFC 8949) into a [JsonElement] for display, or null when [bytes] is not
 * exactly one well-formed item. kotlinx-serialization-cbor only decodes into known classes, and the
 * bodies shown here (ASL error maps, CBOR-encoded bundles) have no schema on our side.
 *
 * Display mapping, not a lossless one: byte strings become base64, tags are dropped in favour of
 * their content, map keys are stringified and `undefined` becomes null.
 */
fun decodeCborToJson(bytes: ByteArray): JsonElement? {
    if (bytes.isEmpty()) return null
    return runCatching {
        val reader = CborReader(bytes)
        val item = reader.readItem()
        check(reader.atEnd()) { "trailing bytes after CBOR item" }
        item
    }.getOrNull()
}

private class CborReader(private val bytes: ByteArray) {
    private var pos = 0
    private var depth = 0

    fun atEnd() = pos == bytes.size

    fun readItem(): JsonElement {
        val initial = byte()
        return readItem(initial ushr 5, initial and 0x1f)
    }

    private fun readItem(major: Int, info: Int): JsonElement {
        if (++depth > MAX_DEPTH) error("CBOR nesting too deep")
        try {
            return when (major) {
                0 -> JsonPrimitive(argument(info).toUnsignedBigInteger())
                1 -> JsonPrimitive(BigInteger.ONE.negate() - argument(info).toUnsignedBigInteger())
                2 -> JsonPrimitive(Base64.encode(readString(major, info)))
                3 -> JsonPrimitive(readString(major, info).decodeToString(throwOnInvalidSequence = true))
                4 -> JsonArray(readContainer(info) { readItem() })
                5 -> JsonObject(
                    buildMap {
                        readContainer(info) { put(keyText(readItem()), readItem()) }
                    },
                )
                6 -> {
                    argument(info)
                    readItem()
                }
                else -> readSimple(info)
            }
        } finally {
            depth--
        }
    }

    private fun readSimple(info: Int): JsonElement = when (info) {
        20 -> JsonPrimitive(false)
        21 -> JsonPrimitive(true)
        22, 23 -> JsonNull
        25 -> JsonPrimitive(halfToFloat(readUInt(2).toInt()))
        26 -> JsonPrimitive(Float.fromBits(readUInt(4).toInt()))
        27 -> JsonPrimitive(Double.fromBits(readUInt(8)))
        else -> error("unsupported CBOR simple value $info")
    }

    /** Elements of an array or map (definite or indefinite length), via [readEntry] per element. */
    private fun <T> readContainer(info: Int, readEntry: () -> T): List<T> {
        if (info == INDEFINITE) {
            return buildList {
                while (peek() != BREAK) add(readEntry())
                pos++
            }
        }
        val count = argument(info)
        require(count in 0..bytes.size.toLong()) { "CBOR container length $count exceeds body" }
        return List(count.toInt()) { readEntry() }
    }

    private fun readString(major: Int, info: Int): ByteArray {
        if (info != INDEFINITE) return take(argument(info))
        val out = java.io.ByteArrayOutputStream()
        while (peek() != BREAK) {
            val chunk = byte()
            require(chunk ushr 5 == major && chunk and 0x1f != INDEFINITE) { "bad CBOR string chunk" }
            out.write(take(argument(chunk and 0x1f)))
        }
        pos++
        return out.toByteArray()
    }

    private fun argument(info: Int): Long = when {
        info < 24 -> info.toLong()
        info == 24 -> readUInt(1)
        info == 25 -> readUInt(2)
        info == 26 -> readUInt(4)
        info == 27 -> readUInt(8)
        else -> error("bad CBOR additional info $info")
    }

    private fun readUInt(size: Int): Long {
        var value = 0L
        repeat(size) { value = (value shl 8) or byte().toLong() }
        return value
    }

    private fun take(length: Long): ByteArray {
        require(length in 0..(bytes.size - pos).toLong()) { "CBOR string runs past end of body" }
        return bytes.copyOfRange(pos, pos + length.toInt()).also { pos += length.toInt() }
    }

    private fun byte(): Int {
        require(pos < bytes.size) { "unexpected end of CBOR" }
        return bytes[pos++].toInt() and 0xff
    }

    private fun peek(): Int {
        require(pos < bytes.size) { "unexpected end of CBOR" }
        return bytes[pos].toInt() and 0xff
    }

    private fun keyText(key: JsonElement): String =
        (key as? JsonPrimitive)?.takeIf { it.isString }?.content ?: key.toString()

    private companion object {
        const val INDEFINITE = 31
        const val BREAK = 0xff
        const val MAX_DEPTH = 256
    }
}

/** A CBOR argument is an unsigned 64-bit value; read back from a signed Long. */
private fun Long.toUnsignedBigInteger(): BigInteger =
    if (this >= 0) BigInteger.valueOf(this) else BigInteger.valueOf(this).add(BigInteger.ONE.shiftLeft(64))

private fun halfToFloat(half: Int): Float {
    val sign = if (half and 0x8000 != 0) -1f else 1f
    val exp = (half ushr 10) and 0x1f
    val mant = half and 0x3ff
    return sign * when (exp) {
        0 -> mant * 2f.pow(-24)
        31 -> if (mant == 0) Float.POSITIVE_INFINITY else Float.NaN
        else -> (1f + mant / 1024f) * 2f.pow(exp - 15)
    }
}

private fun Float.pow(n: Int): Float = Math.pow(toDouble(), n.toDouble()).toFloat()
