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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CborJsonTest {
    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun decode(hexBytes: String): String? = decodeCborToJson(hex(hexBytes))?.toString()

    @Test
    fun decodesRfc8949Vectors() {
        assertEquals("0", decode("00"))
        assertEquals("1000000", decode("1a000f4240"))
        assertEquals("18446744073709551615", decode("1bffffffffffffffff"))
        assertEquals("-1000", decode("3903e7"))
        assertEquals("1.5", decode("f93e00"))
        assertEquals("1.1", decode("fb3ff199999999999a"))
        assertEquals("false", decode("f4"))
        assertEquals("null", decode("f6"))
        assertEquals("\"ü\"", decode("62c3bc"))
        assertEquals("\"AQIDBA==\"", decode("4401020304"))
        assertEquals("[1,[2,3],[4,5]]", decode("8301820203820405"))
        assertEquals("""{"a":1,"b":[2,3]}""", decode("a26161016162820203"))
        assertEquals("""{"1":2,"3":4}""", decode("a201020304"))
        assertEquals("\"2013-03-21T20:04:00Z\"", decode("c074323031332d30332d32315432303a30343a30305a"))
    }

    @Test
    fun decodesIndefiniteLengthItems() {
        assertEquals("[1,[2,3],[4,5]]", decode("9f018202039f0405ffff"))
        assertEquals("""{"Fun":true,"Amt":-2}""", decode("bf6346756ef563416d7421ff"))
        assertEquals("\"streaming\"", decode("7f657374726561646d696e67ff"))
    }

    @Test
    fun decodesAslErrorMap() {
        val body = hex(
            "a3" +
                "6b4d65737361676554797065" + "654572726f72" +
                "694572726f72436f6465" + "1903e9" +
                "6c4572726f724d657373616765" + "6c756e6b6e6f776e2063696420",
        )
        assertEquals(
            """{"MessageType":"Error","ErrorCode":1001,"ErrorMessage":"unknown cid "}""",
            decodeCborToJson(body).toString(),
        )
    }

    @Test
    fun rejectsNonCbor() {
        assertNull(decodeCborToJson(ByteArray(0)))
        assertNull(decodeCborToJson("<html><body>502 Bad Gateway</body></html>".toByteArray()))
        assertNull(decode("8301"))
        assertNull(decode("0001"))
        assertNull(decode("62c3"))
        assertNull(decode("9b7fffffffffffffff"))
    }
}
