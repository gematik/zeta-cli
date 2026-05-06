package de.gematik.connector

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectorClientTest {

    private val baseUrl = "http://konnektor.test"
    private val sdsXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <ns2:ConnectorServices xmlns:ns2="http://ws.gematik.de/conn/ServiceDirectory/v3.1"
                               xmlns:ns3="http://ws.gematik.de/conn/ServiceInformation/v2.0">
          <VERS:ProductInformation xmlns:VERS="http://ws.gematik.de/int/version/ProductInformation/v1.1">
            <VERS:ProductTypeInformation>
              <VERS:ProductType>Connector</VERS:ProductType>
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
              <ns3:Abstract>EventService</ns3:Abstract>
              <ns3:Versions>
                <ns3:Version TargetNamespace="http://ws.gematik.de/conn/EventService/v7.2" Version="7.2.0">
                  <ns3:Abstract>EventService v7.2</ns3:Abstract>
                  <ns3:EndpointTLS Location="$baseUrl/ws/EventService"/>
                </ns3:Version>
              </ns3:Versions>
            </ns3:Service>
          </ns3:ServiceInformation>
        </ns2:ConnectorServices>
    """.trimIndent()

    private val getCardsResponseXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
          <soap:Body>
            <ns4:GetCardsResponse xmlns:ns4="http://ws.gematik.de/conn/EventService/v7.2"
                                  xmlns:ns2="http://ws.gematik.de/conn/ConnectorCommon/v5.0"
                                  xmlns:ns3="http://ws.gematik.de/conn/CardService/v8.1"
                                  xmlns:ns5="http://ws.gematik.de/conn/CardServiceCommon/v2.0">
              <ns2:Status>
                <ns2:Result>OK</ns2:Result>
              </ns2:Status>
              <ns3:Cards>
                <ns3:Card>
                  <ns2:CardHandle>card-smcb-1</ns2:CardHandle>
                  <ns5:CardType>SMC-B</ns5:CardType>
                  <ns5:Iccsn>80276123456789010001</ns5:Iccsn>
                  <ns5:CtId>CT_ID_1</ns5:CtId>
                  <ns5:SlotId>1</ns5:SlotId>
                  <ns3:InsertTime>2024-01-15T10:30:00Z</ns3:InsertTime>
                  <ns3:CardHolderName>Test Practice</ns3:CardHolderName>
                </ns3:Card>
                <ns3:Card>
                  <ns2:CardHandle>card-hba-1</ns2:CardHandle>
                  <ns5:CardType>HBA</ns5:CardType>
                  <ns5:Iccsn>80276123456789020001</ns5:Iccsn>
                  <ns5:CtId>CT_ID_1</ns5:CtId>
                  <ns5:SlotId>2</ns5:SlotId>
                  <ns3:InsertTime>2024-01-15T11:00:00Z</ns3:InsertTime>
                  <ns3:CardHolderName>Dr. Test</ns3:CardHolderName>
                </ns3:Card>
              </ns3:Cards>
            </ns4:GetCardsResponse>
          </soap:Body>
        </soap:Envelope>
    """.trimIndent()

    private val getCardsFaultXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
          <soap:Body>
            <soap:Fault>
              <faultcode>soap:Server</faultcode>
              <faultstring>internal error</faultstring>
            </soap:Fault>
          </soap:Body>
        </soap:Envelope>
    """.trimIndent()

    private fun konnektor(soapHandler: () -> String): ConnectorClient = runBlocking {
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/connector.sds") ->
                    respond(sdsXml, HttpStatusCode.OK, headersOf("Content-Type", "text/xml"))
                request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/ws/EventService") ->
                    respond(soapHandler(), HttpStatusCode.OK, headersOf("Content-Type", "text/xml"))
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val httpClient = HttpClient(engine)
        val dotkon = Dotkon(
            url = baseUrl,
            mandantId = "M1", workplaceId = "W1", clientSystemId = "C1",
            credentials = Credentials.Basic("u", "p"),
        )
        ConnectorClient.connect(httpClient, dotkon)
    }

    @Test
    fun `getAllCards round-trips a GetCards response`() = runBlocking {
        val client = konnektor { getCardsResponseXml }
        val cards = client.getAllCards()
        assertEquals(2, cards.size)
        assertEquals("card-smcb-1", cards[0].cardHandle)
        assertEquals("CT_ID_1", cards[0].ctId)
        assertEquals("Test Practice", cards[0].cardHolderName)
        assertEquals("card-hba-1", cards[1].cardHandle)
    }

    @Test
    fun `getAllCards surfaces SOAP faults as SoapFaultException`() = runBlocking {
        val client = konnektor { getCardsFaultXml }
        val ex = assertThrows(SoapFaultException::class.java) {
            runBlocking { client.getAllCards() }
        }
        assertTrue(ex.message!!.contains("GetCards"))
    }

    @Test
    fun `latestServiceProxy picks the highest semver`() = runBlocking {
        val client = konnektor { getCardsResponseXml }
        val proxy = client.latestServiceProxy(ServiceNames.EventService)
        assertEquals("7.2.0", proxy.serviceVersion.version)
        assertEquals("$baseUrl/ws/EventService", proxy.endpoint)
    }
}
