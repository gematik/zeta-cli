package de.gematik.connector

import de.gematik.connector.api.gematik.conn.authsignatureservice74.ExternalAuthenticateResponseEnvelope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SoapFaultExceptionTest {

    /** A SOAP 1.1 fault as a Konnektor sends it: fault children unqualified, the detail in the gematik namespace. */
    private fun wireFault(detail: String = TRACE) =
        """<?xml version="1.0" encoding="UTF-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
          <soap:Body>
            <soap:Fault>
              <faultcode>soap:Server</faultcode>
              <faultstring>Technical Error</faultstring>
              $detail
            </soap:Fault>
          </soap:Body>
        </soap:Envelope>"""

    @Test
    fun `the faultstring and the gematik error survive decoding`() {
        val fault = SoapFault.parse(wireFault()) ?: error("expected a parsed fault")

        assertEquals("soap:Server", fault.faultcode)
        assertEquals("Technical Error", fault.faultstring)
        assertEquals("4018", fault.errorCode)
        assertEquals("Der Aufruf ist nicht zulaessig — Karte SMC-B-255 ist nicht freigeschaltet", fault.errorText)
    }

    @Test
    fun `the exception message names the operation and what the Konnektor said`() {
        val ex = SoapFaultException("ExternalAuthenticate(cardHandle=SMC-B-255)", SoapFault.parse(wireFault()))

        assertEquals(
            "ExternalAuthenticate(cardHandle=SMC-B-255) reported a SOAP fault: Technical Error " +
                "(code 4018: Der Aufruf ist nicht zulaessig — Karte SMC-B-255 ist nicht freigeschaltet)",
            ex.message,
        )
        assertEquals("Technical Error", ex.faultstring)
    }

    @Test
    fun `a fault without a gematik detail still reports its faultstring`() {
        val fault = SoapFault.parse(wireFault(detail = "")) ?: error("expected a parsed fault")

        assertNull(fault.errorCode)
        assertEquals("Technical Error", fault.describe())
    }

    @Test
    fun `a body with no fault in it parses to nothing`() {
        assertNull(SoapFault.parse("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body/></soap:Envelope>"))
        assertNull(SoapFault.parse("not xml at all"))
    }

    @Test
    fun `an unparseable body still surfaces as a fault, just a plainer one`() {
        val ex = SoapFaultException("SecureSendAPDU", fault = null)

        assertEquals("SecureSendAPDU reported a SOAP fault", ex.message)
    }

    @Test
    fun `a schema-complete fault decodes into the generated envelope as well`() {
        val envelope = defaultXml.decodeFromString(
            ExternalAuthenticateResponseEnvelope.serializer(),
            wireFault(detail = FULL_TRACE),
        )
        val fault = envelope.body.fault ?: error("expected a decoded fault")

        assertEquals("Technical Error", fault.faultstring)
        assertEquals(4018, fault.detail?.error?.trace?.first()?.code)
    }

    @Test
    fun `a fault the generated types reject is still readable`() {
        // TRACE leaves out fields the gematik schema marks mandatory, which is enough to fail the
        // typed decode outright — the whole reason the fault is parsed off the raw body instead.
        assertThrows(Exception::class.java) {
            defaultXml.decodeFromString(ExternalAuthenticateResponseEnvelope.serializer(), wireFault())
        }
        assertEquals("4018", SoapFault.parse(wireFault())?.errorCode)
    }

    @Test
    fun `the last trace wins when a Konnektor stacks several`() {
        val two = """
            <detail>
              <err:Error xmlns:err="http://ws.gematik.de/tel/error/v2.0">
                <err:Trace><err:Code>1</err:Code><err:ErrorText>outer</err:ErrorText></err:Trace>
                <err:Trace><err:Code>4018</err:Code><err:ErrorText>root cause</err:ErrorText></err:Trace>
              </err:Error>
            </detail>
        """.trimIndent()
        val fault = SoapFault.parse(wireFault(detail = two)) ?: error("expected a parsed fault")

        assertEquals("4018", fault.errorCode)
        assertTrue(fault.describe()!!.contains("root cause"))
    }

    private companion object {
        /** Everything the gematik schema marks mandatory, so the generated types accept it too. */
        val FULL_TRACE = """
            <detail>
              <err:Error xmlns:err="http://ws.gematik.de/tel/error/v2.0">
                <err:MessageID>urn:uuid:1</err:MessageID>
                <err:Timestamp>2026-09-07T10:00:00Z</err:Timestamp>
                <err:Trace>
                  <err:EventID>1</err:EventID>
                  <err:Instance>KON</err:Instance>
                  <err:LogReference>ref</err:LogReference>
                  <err:CompType>Konnektor</err:CompType>
                  <err:Code>4018</err:Code>
                  <err:Severity>Error</err:Severity>
                  <err:ErrorType>Technical</err:ErrorType>
                  <err:ErrorText>Der Aufruf ist nicht zulaessig</err:ErrorText>
                </err:Trace>
              </err:Error>
            </detail>
        """.trimIndent()

        val TRACE = """
            <detail>
              <err:Error xmlns:err="http://ws.gematik.de/tel/error/v2.0" MessageID="urn:uuid:1" Timestamp="2026-09-07T10:00:00Z">
                <err:Trace>
                  <err:Code>4018</err:Code>
                  <err:ErrorType>Technical</err:ErrorType>
                  <err:Severity>Error</err:Severity>
                  <err:ErrorText>Der Aufruf ist nicht zulaessig</err:ErrorText>
                  <err:Detail>Karte SMC-B-255 ist nicht freigeschaltet</err:Detail>
                </err:Trace>
              </err:Error>
            </detail>
        """.trimIndent()
    }
}
