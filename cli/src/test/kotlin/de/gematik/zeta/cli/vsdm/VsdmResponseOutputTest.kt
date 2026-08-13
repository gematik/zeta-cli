package de.gematik.zeta.cli.vsdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VsdmResponseOutputTest {
    @Test
    fun httpResponseText_formatsStatusHeadersBlankLineThenBody() {
        val headers = linkedMapOf(
            "ETag" to "\"a1b2\"",
            "PZ" to "pz-value",
            "Content-Type" to "application/fhir+json",
        )
        val out = httpResponseText(200, "OK", headers, """{"resourceType":"Bundle"}""".toByteArray())

        val expected = buildString {
            append("HTTP/1.1 200 OK\n")
            append("ETag: \"a1b2\"\n")
            append("PZ: pz-value\n")
            append("Content-Type: application/fhir+json\n")
            append("\n")
            append("""{"resourceType":"Bundle"}""")
        }
        assertEquals(expected, out)
    }

    @Test
    fun httpResponseText_omitsReasonWhenBlank_andHandlesNoHeaders() {
        val out = httpResponseText(204, "", emptyMap(), ByteArray(0))
        assertEquals("HTTP/1.1 204\n\n", out)
    }
}
