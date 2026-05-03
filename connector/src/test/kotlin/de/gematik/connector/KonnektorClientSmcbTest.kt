package de.gematik.connector

import de.gematik.connector.crypto.testCertWithTelematikId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.security.cert.X509Certificate
import java.util.Date
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

@OptIn(ExperimentalEncodingApi::class)
class KonnektorClientSmcbTest {

    private val baseUrl = "http://konnektor.test"
    private val sdsXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <ns2:ConnectorServices xmlns:ns2="http://ws.gematik.de/conn/ServiceDirectory/v3.1"
                               xmlns:ns3="http://ws.gematik.de/conn/ServiceInformation/v2.0">
          <VERS:ProductInformation xmlns:VERS="http://ws.gematik.de/int/version/ProductInformation/v1.1">
            <VERS:ProductTypeInformation>
              <VERS:ProductType>Konnektor</VERS:ProductType>
              <VERS:ProductTypeVersion>4.0.0</VERS:ProductTypeVersion>
            </VERS:ProductTypeInformation>
            <VERS:ProductIdentification>
              <VERS:ProductVendorID>Test</VERS:ProductVendorID>
              <VERS:ProductCode>TEST</VERS:ProductCode>
              <VERS:ProductVersion>
                <VERS:Local><VERS:HWVersion>1.0.0</VERS:HWVersion><VERS:FWVersion>1.0.0</VERS:FWVersion></VERS:Local>
              </VERS:ProductVersion>
            </VERS:ProductIdentification>
          </VERS:ProductInformation>
          <ns3:ServiceInformation>
            <ns3:Service Name="EventService">
              <ns3:Versions>
                <ns3:Version TargetNamespace="http://ws.gematik.de/conn/EventService/v7.2" Version="7.2.0">
                  <ns3:EndpointTLS Location="$baseUrl/ws/EventService"/>
                </ns3:Version>
              </ns3:Versions>
            </ns3:Service>
            <ns3:Service Name="CertificateService">
              <ns3:Versions>
                <ns3:Version TargetNamespace="http://ws.gematik.de/conn/CertificateService/v6.0" Version="6.0.1">
                  <ns3:EndpointTLS Location="$baseUrl/ws/CertificateService"/>
                </ns3:Version>
              </ns3:Versions>
            </ns3:Service>
          </ns3:ServiceInformation>
        </ns2:ConnectorServices>
    """.trimIndent()

    /**
     * A `GetCards(SMC-B)` response carrying [handles] in order. Each entry is `(handle,
     * iccsn)`. The card uses an arbitrary fixed CtId / SlotId since findCardHandleByTelematikId
     * only inspects handles.
     */
    private fun smcbCardsResponse(vararg handles: Pair<String, String>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">""")
        append("<soap:Body>")
        append("""<ns4:GetCardsResponse""")
        append(""" xmlns:ns4="http://ws.gematik.de/conn/EventService/v7.2"""")
        append(""" xmlns:ns2="http://ws.gematik.de/conn/ConnectorCommon/v5.0"""")
        append(""" xmlns:ns3="http://ws.gematik.de/conn/CardService/v8.1"""")
        append(""" xmlns:ns5="http://ws.gematik.de/conn/CardServiceCommon/v2.0">""")
        append("<ns2:Status><ns2:Result>OK</ns2:Result></ns2:Status>")
        append("<ns3:Cards>")
        for ((handle, iccsn) in handles) {
            append("<ns3:Card>")
            append("<ns2:CardHandle>$handle</ns2:CardHandle>")
            append("<ns5:CardType>SMC-B</ns5:CardType>")
            append("<ns5:Iccsn>$iccsn</ns5:Iccsn>")
            append("<ns5:CtId>CT_ID_1</ns5:CtId>")
            append("<ns5:SlotId>1</ns5:SlotId>")
            append("<ns3:InsertTime>2024-01-15T10:30:00Z</ns3:InsertTime>")
            append("</ns3:Card>")
        }
        append("</ns3:Cards></ns4:GetCardsResponse></soap:Body></soap:Envelope>")
    }

    /** A `ReadCardCertificateResponse` with a base64-encoded DER certificate. */
    private fun readCertResponse(cert: X509Certificate): String {
        val b64 = Base64.encode(cert.encoded)
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <ns2:ReadCardCertificateResponse
                    xmlns:ns2="http://ws.gematik.de/conn/CertificateService/v6.0"
                    xmlns:ns3="http://ws.gematik.de/conn/ConnectorCommon/v5.0"
                    xmlns:ns4="http://ws.gematik.de/conn/CertificateServiceCommon/v2.0">
                  <ns3:Status><ns3:Result>OK</ns3:Result></ns3:Status>
                  <ns4:X509DataInfoList>
                    <ns4:X509DataInfo>
                      <ns4:CertRef>C.AUT</ns4:CertRef>
                      <ns4:X509Data>
                        <ns4:X509IssuerSerial>
                          <ns4:X509IssuerName>CN=Test</ns4:X509IssuerName>
                          <ns4:X509SerialNumber>1</ns4:X509SerialNumber>
                        </ns4:X509IssuerSerial>
                        <ns4:X509SubjectName>CN=test-smcb</ns4:X509SubjectName>
                        <ns4:X509Certificate>$b64</ns4:X509Certificate>
                      </ns4:X509Data>
                    </ns4:X509DataInfo>
                  </ns4:X509DataInfoList>
                </ns2:ReadCardCertificateResponse>
              </soap:Body>
            </soap:Envelope>
        """.trimIndent()
    }

    /**
     * Build a Konnektor wired to canned responses. [cardsXml] is returned for every
     * `GetCards` call; [readCertFor] resolves the response for each `ReadCardCertificate`
     * by looking at the `<CardHandle>` in the request body — returns null to surface a
     * 404, mirroring "card not found" on a real Konnektor.
     */
    private fun konnektor(
        cardsXml: String,
        readCertFor: (cardHandle: String) -> String?,
    ): KonnektorClient = runBlocking {
        // Match <CardHandle ...>VALUE</...CardHandle> tolerating namespace prefix and xmlns attrs.
        val cardHandlePattern = Regex("""<[^>]*\bCardHandle\b[^>]*>([^<]+)</[^>]*CardHandle>""")
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            when {
                req.method == HttpMethod.Get && path.endsWith("/connector.sds") ->
                    respond(sdsXml, HttpStatusCode.OK, headersOf("Content-Type", "text/xml"))
                req.method == HttpMethod.Post && path.endsWith("/ws/EventService") ->
                    respond(cardsXml, HttpStatusCode.OK, headersOf("Content-Type", "text/xml"))
                req.method == HttpMethod.Post && path.endsWith("/ws/CertificateService") -> {
                    val body = req.body.toByteArray().toString(Charsets.UTF_8)
                    val handle = cardHandlePattern.find(body)?.groupValues?.get(1)
                        ?: error("ReadCardCertificate request without <CardHandle>: $body")
                    val xml = readCertFor(handle) ?: return@MockEngine respond("", HttpStatusCode.NotFound)
                    respond(xml, HttpStatusCode.OK, headersOf("Content-Type", "text/xml"))
                }
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val httpClient = HttpClient(engine)
        val dotkon = Dotkon(
            url = baseUrl,
            mandantId = "M1", workplaceId = "W1", clientSystemId = "C1",
            credentials = Credentials.Basic("u", "p"),
        )
        KonnektorClient.connect(httpClient, dotkon)
    }

    @Test
    fun `findCardHandleByTelematikId returns the matching handle`() = runBlocking {
        val cert = testCertWithTelematikId("1-SMC-B-Testkarte-001")
        val client = konnektor(
            cardsXml = smcbCardsResponse("card-smcb-1" to "iccsn-1"),
            readCertFor = { handle -> if (handle == "card-smcb-1") readCertResponse(cert) else null },
        )
        assertEquals("card-smcb-1", client.findCardHandleByTelematikId("1-SMC-B-Testkarte-001"))
    }

    @Test
    fun `findCardHandleByTelematikId picks newest cert when multiple match`() = runBlocking {
        // Two cards with the same Telematik-ID, different notBefore — typical card-renewal window.
        val older = testCertWithTelematikId(
            "1-SMC-B-Testkarte-renewed",
            notBefore = Date(System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000),
        )
        val newer = testCertWithTelematikId(
            "1-SMC-B-Testkarte-renewed",
            notBefore = Date(),
        )
        val client = konnektor(
            cardsXml = smcbCardsResponse("card-old" to "iccsn-old", "card-new" to "iccsn-new"),
            readCertFor = { handle ->
                when (handle) {
                    "card-old" -> readCertResponse(older)
                    "card-new" -> readCertResponse(newer)
                    else -> null
                }
            },
        )
        assertEquals("card-new", client.findCardHandleByTelematikId("1-SMC-B-Testkarte-renewed"))
    }

    @Test
    fun `findCardHandleByTelematikId throws CardNotFoundException when no match`() = runBlocking {
        val cert = testCertWithTelematikId("1-SMC-B-Testkarte-other")
        val client = konnektor(
            cardsXml = smcbCardsResponse("card-smcb-1" to "iccsn-1"),
            readCertFor = { handle -> if (handle == "card-smcb-1") readCertResponse(cert) else null },
        )
        val ex = assertThrows(CardNotFoundException::class.java) {
            runBlocking { client.findCardHandleByTelematikId("1-SMC-B-NoSuchTID") }
        }
        // Message should mention the searched TID.
        assert(ex.message!!.contains("1-SMC-B-NoSuchTID")) { "actual: ${ex.message}" }
    }

    @Test
    fun `listSmcbCards returns all cards with extracted Telematik-IDs`() = runBlocking {
        val certA = testCertWithTelematikId("tid-A")
        val certB = testCertWithTelematikId("tid-B")
        val client = konnektor(
            cardsXml = smcbCardsResponse("card-A" to "iccsn-A", "card-B" to "iccsn-B"),
            readCertFor = { handle ->
                when (handle) {
                    "card-A" -> readCertResponse(certA)
                    "card-B" -> readCertResponse(certB)
                    else -> null
                }
            },
        )
        val cards = client.listSmcbCards()
        assertEquals(2, cards.size)
        assertEquals("tid-A", cards.first { it.cardHandle == "card-A" }.telematikId)
        assertEquals("tid-B", cards.first { it.cardHandle == "card-B" }.telematikId)
    }

    @Test
    fun `listSmcbCards survives unreadable cert with telematikId=null`() = runBlocking {
        val client = konnektor(
            cardsXml = smcbCardsResponse("card-broken" to "iccsn-broken"),
            readCertFor = { null }, // every read fails -> 404 -> ConnectorServicesException-ish
        )
        val cards = client.listSmcbCards()
        assertEquals(1, cards.size)
        assertNull(cards[0].telematikId)
        assertNull(cards[0].certificate)
    }
}
