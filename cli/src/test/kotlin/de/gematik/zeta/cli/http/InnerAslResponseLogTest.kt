package de.gematik.zeta.cli.http

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InnerAslResponseLogTest {
    @Test
    fun innerAslResponseWireDump_isParsedAsAResponseWithBody() {
        val dump = innerAslResponseWireDump(
            status = 200,
            reason = "OK",
            headers = mapOf("Content-Type" to "application/fhir+json", "Content-Length" to "27"),
            body = """{"resourceType":"Bundle"}""".toByteArray(),
        )

        // Fed to the same reformatter as the SDK's wire dumps, it renders a Response section
        // (not a Request) with the decoded headers and body.
        val rendered = reformatHttpLog(dump)

        listOf("Response", "200 OK", "Content-Type: application/fhir+json", "resourceType").forEach {
            assertTrue(rendered.contains(it), "expected rendered wire log to contain '$it', was:\n$rendered")
        }
    }
}
