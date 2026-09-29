package de.gematik.connector

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConsumerClientTest {

    private fun dotkon(certificateVersion: String = "3.0.1", signatureVersion: String = "3.2.1") = parseDotkon(
        """
        {
            "product": "consumer",
            "url": "https://bc.test:8443/",
            "serviceEndpoints": [
                { "name": "CertificateService", "path": "/ws/CertificateService", "version": "$certificateVersion" },
                { "name": "SignatureService", "path": "/ws/SignatureService", "version": "$signatureVersion" }
            ],
            "credentials": { "type": "none" }
        }
        """.trimIndent(),
        envLookup = { null },
    )

    private val dotkon = dotkon()

    private fun readCertificateResponseXml(certNs: String = "v3.1", commonNs: String = "v2.1") = """
        <?xml version="1.0" encoding="UTF-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
          <soap:Body>
            <CERT:ReadCertificateResponse xmlns:CERT="http://ws.gematik.de/consumer/CertificateService/$certNs"
                                          xmlns:CONSUMER="http://ws.gematik.de/consumer/ConsumerCommon/v2.0"
                                          xmlns:CERTCMN="http://ws.gematik.de/consumer/CertificateServiceCommon/$commonNs">
              <CONSUMER:Status><CONSUMER:Result>OK</CONSUMER:Result></CONSUMER:Status>
              <CERTCMN:X509DataInfoList>
                <CERTCMN:X509DataInfo>
                  <CERTCMN:CertRef>C.AUT</CERTCMN:CertRef>
                  <CERTCMN:X509Data>
                    <CERTCMN:X509IssuerSerial>
                      <CERTCMN:X509IssuerName>CN=Test CA</CERTCMN:X509IssuerName>
                      <CERTCMN:X509SerialNumber>1</CERTCMN:X509SerialNumber>
                    </CERTCMN:X509IssuerSerial>
                    <CERTCMN:X509SubjectName>CN=Test Org</CERTCMN:X509SubjectName>
                    <CERTCMN:X509Certificate>AQIDBA==</CERTCMN:X509Certificate>
                  </CERTCMN:X509Data>
                </CERTCMN:X509DataInfo>
              </CERTCMN:X509DataInfoList>
            </CERT:ReadCertificateResponse>
          </soap:Body>
        </soap:Envelope>
    """.trimIndent()

    private fun externalAuthenticateResponseXml(sigNs: String = "v3.2") = """
        <?xml version="1.0" encoding="UTF-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
          <soap:Body>
            <SIG:ExternalAuthenticateResponse xmlns:SIG="http://ws.gematik.de/consumer/SignatureService/$sigNs"
                                              xmlns:CONSUMER="http://ws.gematik.de/consumer/ConsumerCommon/v2.0"
                                              xmlns:dss="urn:oasis:names:tc:dss:1.0:core:schema">
              <CONSUMER:Status><CONSUMER:Result>OK</CONSUMER:Result></CONSUMER:Status>
              <dss:SignatureObject>
                <dss:Base64Signature Type="urn:bsi:tr:03111:ecdsa">MAYCAQECAQI=</dss:Base64Signature>
              </dss:SignatureObject>
            </SIG:ExternalAuthenticateResponse>
          </soap:Body>
        </soap:Envelope>
    """.trimIndent()

    private val noCertificatesFaultXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
          <soap:Body>
            <soap:Fault>
              <faultcode>soap:Server</faultcode>
              <faultstring>Technical Error</faultstring>
              <detail>
                <err:Error xmlns:err="http://ws.gematik.de/tel/error/v2.0">
                  <err:MessageID>urn:uuid:1</err:MessageID>
                  <err:Timestamp>2026-09-28T10:00:00Z</err:Timestamp>
                  <err:Trace>
                    <err:EventID>1</err:EventID>
                    <err:Instance>bc</err:Instance>
                    <err:LogReference>1</err:LogReference>
                    <err:CompType>Basis-Consumer</err:CompType>
                    <err:Code>4258</err:Code>
                    <err:Severity>Error</err:Severity>
                    <err:ErrorType>Technical</err:ErrorType>
                    <err:ErrorText>Zertifikate nicht vorhanden auf Identität: bc-handle-1</err:ErrorText>
                  </err:Trace>
                </err:Error>
              </detail>
            </soap:Fault>
          </soap:Body>
        </soap:Envelope>
    """.trimIndent()

    private data class Recorded(val method: HttpMethod, val url: String, val soapAction: String?, val body: String)

    private fun consumer(
        recorded: MutableList<Recorded>,
        dotkon: Dotkon = this.dotkon,
        respond: (Recorded) -> Pair<String, HttpStatusCode>,
    ): ConsumerClient {
        val engine = MockEngine { req ->
            val r = Recorded(req.method, req.url.toString(), req.headers["SOAPAction"], req.body.toByteArray().decodeToString())
            recorded += r
            val (body, status) = respond(r)
            respond(body, status, headersOf("Content-Type", "text/xml"))
        }
        return ConsumerClient(HttpClient(engine), dotkon)
    }

    @Test
    fun `ReadCertificate goes to the configured path without context`() = runBlocking {
        val recorded = mutableListOf<Recorded>()
        val cert = consumer(recorded) { readCertificateResponseXml() to HttpStatusCode.OK }
            .readCardAutCertificate("bc-handle-1")

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), cert)
        val req = recorded.single()
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("https://bc.test:8443/ws/CertificateService", req.url)
        assertEquals("http://ws.gematik.de/consumer/CertificateService/v3.0#ReadCertificate", req.soapAction)
        assertTrue("bc-handle-1" in req.body && "C.AUT" in req.body && "ECC" in req.body, req.body)
        assertFalse("Context" in req.body, req.body)
    }

    @Test
    fun `ExternalAuthenticate signs through the SignatureService`() = runBlocking {
        val recorded = mutableListOf<Recorded>()
        val sig = consumer(recorded) { externalAuthenticateResponseXml() to HttpStatusCode.OK }
            .externalAuthenticate("bc-handle-1", ByteArray(32) { it.toByte() })

        assertArrayEquals(byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02), sig)
        val req = recorded.single()
        assertEquals("https://bc.test:8443/ws/SignatureService", req.url)
        assertEquals("http://ws.gematik.de/consumer/SignatureService/v3.2#ExternalAuthenticate", req.soapAction)
        assertTrue("urn:bsi:tr:03111:ecdsa" in req.body, req.body)
        assertFalse("Context" in req.body, req.body)
    }

    @Test
    fun `a Basis-Consumer fault surfaces its error code`() {
        val recorded = mutableListOf<Recorded>()
        val client = consumer(recorded) { noCertificatesFaultXml to HttpStatusCode.InternalServerError }
        val ex = assertThrows<SoapFaultException> { runBlocking { client.readCardAutCertificate("bc-handle-1") } }
        assertTrue("4258" in ex.message!!, ex.message)
    }

    @Test
    fun `ReadCertificate speaks the configured version`() = runBlocking {
        // version -> (SOAPAction version, payload namespace, CertificateServiceCommon namespace)
        mapOf("3.0.0" to Triple("v3.0", "v3.0", "v2.0"), "3.0.1" to Triple("v3.0", "v3.1", "v2.1"))
            .forEach { (version, ns) ->
                val (action, payload, common) = ns
                val recorded = mutableListOf<Recorded>()
                val cert = consumer(recorded, dotkon(certificateVersion = version)) {
                    readCertificateResponseXml(payload, common) to HttpStatusCode.OK
                }.readCardAutCertificate("bc-handle-1")

                assertArrayEquals(byteArrayOf(1, 2, 3, 4), cert, version)
                val req = recorded.single()
                assertEquals("http://ws.gematik.de/consumer/CertificateService/$action#ReadCertificate", req.soapAction, version)
                assertTrue("xmlns=\"http://ws.gematik.de/consumer/CertificateService/$payload\"" in req.body, "$version: ${req.body}")
            }
    }

    @Test
    fun `ExternalAuthenticate speaks the configured version`() = runBlocking {
        // version -> (namespace, whether its schema has OptionalInputs)
        mapOf("3.0.0" to ("v3.0" to true), "3.1.0" to ("v3.1" to false), "3.2.0" to ("v3.2" to false), "3.2.1" to ("v3.2" to true))
            .forEach { (version, expected) ->
                val (ns, optionalInputs) = expected
                val recorded = mutableListOf<Recorded>()
                val sig = consumer(recorded, dotkon(signatureVersion = version)) {
                    externalAuthenticateResponseXml(ns) to HttpStatusCode.OK
                }.externalAuthenticate("bc-handle-1", ByteArray(32) { it.toByte() })

                assertArrayEquals(byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02), sig, version)
                val req = recorded.single()
                assertEquals("http://ws.gematik.de/consumer/SignatureService/$ns#ExternalAuthenticate", req.soapAction, version)
                assertTrue("xmlns=\"http://ws.gematik.de/consumer/SignatureService/$ns\"" in req.body, "$version: ${req.body}")
                assertEquals(optionalInputs, "OptionalInputs" in req.body, "$version: ${req.body}")
            }
    }
}
