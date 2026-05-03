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
