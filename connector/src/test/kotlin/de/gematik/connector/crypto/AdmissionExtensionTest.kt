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
package de.gematik.connector.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AdmissionExtensionTest {

    @Test
    fun `extracts Telematik-ID from admission extension`() {
        val cert = testCertWithTelematikId("1-SMC-B-Testkarte-883110000123456")
        assertEquals("1-SMC-B-Testkarte-883110000123456", cert.telematikId())
    }

    @Test
    fun `returns null when admission extension is absent`() {
        val cert = testCertWithoutAdmission()
        assertNull(cert.telematikId())
    }

    @Test
    fun `parseX509 round-trips a DER cert`() {
        val cert = testCertWithTelematikId("tid-A")
        val der = cert.encoded
        val reparsed = der.parseX509()
        assertEquals("tid-A", reparsed.telematikId())
        assertEquals(cert, reparsed)
    }

    @Test
    fun `parseX509 throws on garbage bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            byteArrayOf(0x01, 0x02, 0x03).parseX509()
        }
    }
}
