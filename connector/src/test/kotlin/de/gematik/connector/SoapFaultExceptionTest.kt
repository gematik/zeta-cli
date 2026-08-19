package de.gematik.connector

import de.gematik.connector.api.gematik.conn.cardservice81.VerifyPinResponseEnvelope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SoapFaultExceptionTest {
    private fun faultEnvelope(code: String, string: String) = VerifyPinResponseEnvelope(
        body = VerifyPinResponseEnvelope.Body(
            fault = VerifyPinResponseEnvelope.Fault(faultcode = code, faultstring = string),
        ),
    )

    @Test
    fun `surfaces the SOAP faultstring in the message and fields`() {
        val ex = SoapFaultException("SecureSendAPDU", faultEnvelope("SOAP-ENV:Server", "Client hat keine Berechtigung auf angef. Resource"))

        assertEquals("Client hat keine Berechtigung auf angef. Resource", ex.faultstring)
        assertEquals("SOAP-ENV:Server", ex.faultcode)
        assertTrue(ex.message!!.contains("SecureSendAPDU reported a SOAP fault: Client hat keine Berechtigung"))
    }

    @Test
    fun `falls back to the generic message when no fault detail is present`() {
        val ex = SoapFaultException("SecureSendAPDU", faultEnvelope(code = "", string = ""))

        assertEquals("SecureSendAPDU reported a SOAP fault", ex.message)
    }
}
