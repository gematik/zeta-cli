package de.gematik.connector

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.measureTimedValue

class ServiceDiscoveryTest {

    @Test
    fun `semverAsNumber sorts as expected`() {
        assertEquals(0, semverAsNumber(""))
        assertEquals(0, semverAsNumber("not-a-version"))
        assertEquals(70_200, semverAsNumber("7.2.0"))
        assertEquals(70_201, semverAsNumber("7.2.1"))
        assertEquals(80_100, semverAsNumber("8.1.0"))
        // Higher major beats higher minor.
        assert(semverAsNumber("8.0.0") > semverAsNumber("7.99.99")) {
            "8.0.0 must outrank 7.99.99"
        }
    }

    @Test
    fun `parses a representative SDS document`() {
        // Mirrors what real Konnektors emit: three distinct namespaces — the wrapper in
        // ServiceDirectory/v3.1, ProductInformation children in int/version/v1.1, and
        // ServiceInformation children in conn/ServiceInformation/v2.0.
        val xml = """
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
                    <VERS:Local>
                      <VERS:HWVersion>1.0.0</VERS:HWVersion>
                      <VERS:FWVersion>1.0.0</VERS:FWVersion>
                    </VERS:Local>
                  </VERS:ProductVersion>
                </VERS:ProductIdentification>
              </VERS:ProductInformation>
              <ns2:TLSMandatory>true</ns2:TLSMandatory>
              <ns2:ClientAutMandatory>true</ns2:ClientAutMandatory>
              <ns3:ServiceInformation>
                <ns3:Service Name="EventService">
                  <ns3:Abstract>EventService</ns3:Abstract>
                  <ns3:Versions>
                    <ns3:Version TargetNamespace="http://ws.gematik.de/conn/EventService/v7.2" Version="7.2.0">
                      <ns3:Abstract>EventService v7.2</ns3:Abstract>
                      <ns3:EndpointTLS Location="https://k.internal/ws/EventService"/>
                    </ns3:Version>
                  </ns3:Versions>
                </ns3:Service>
              </ns3:ServiceInformation>
            </ns2:ConnectorServices>
        """.trimIndent()

        val services = defaultXml.decodeFromString(ConnectorServices.serializer(), xml)
        assertEquals("Konnektor", services.productInformation.productTypeInformation.productType)
        assertEquals(1, services.serviceInformation.service.size)
        val svc = services.serviceInformation.service[0]
        assertEquals(ServiceNames.EventService, svc.name)
        assertEquals("7.2.0", svc.versions.version[0].version)
        assertEquals("https://k.internal/ws/EventService", svc.versions.version[0].endpointTLS?.location)
    }

    @Test
    fun `withRewrittenEndpoints rewrites scheme + host + port keeping the path`() {
        val raw = ConnectorServices(
            productInformation = ProductInformation(
                productTypeInformation = ProductTypeInformation("Konnektor", "4.0.0"),
                productIdentification = ProductIdentification(
                    productVendorId = "v",
                    productCode = "c",
                    productVersion = ProductVersion(LocalProductVersion("1", "1")),
                ),
            ),
            serviceInformation = ServiceInformation(
                service = listOf(
                    Service(
                        name = "EventService",
                        abstract = "",
                        versions = Versions(
                            version = listOf(
                                ServiceVersion(
                                    targetNamespace = "ns",
                                    version = "7.2.0",
                                    endpointTLS = ServiceEndpoint("https://internal-host:443/ws/EventService"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val rewritten = raw.withRewrittenEndpoints("http://localhost:1234")
        assertEquals(
            "http://localhost:1234/ws/EventService",
            rewritten.serviceInformation.service[0].versions.version[0].endpointTLS?.location,
        )
    }

    /**
     * Parsing performance regression test. Uses a real Konnektor-emitted SDS captured into
     * `src/test/resources/connector.sds` (12 services across ~20 versions). The first parse
     * pays xmlutil reflection + class-loading; subsequent parses are steady-state.
     *
     * Hard threshold is intentionally generous (5× the typical warm time observed locally)
     * so this fails only on catastrophic regressions, not on slow CI hardware.
     */
    @Test
    fun `parses real connector sds within budget`() {
        val xml = javaClass.classLoader.getResource("connector.sds")?.readText()
            ?: error("connector.sds not on test classpath — missing from src/test/resources/?")

        val (cold, coldTime) = measureTimedValue {
            defaultXml.decodeFromString(ConnectorServices.serializer(), xml)
        }
        println("SDS cold parse: $coldTime (${cold.serviceInformation.service.size} services, ${xml.length} chars)")

        val warmRuns = 20
        val warmTimes = List(warmRuns) {
            measureTimedValue {
                defaultXml.decodeFromString(ConnectorServices.serializer(), xml)
            }.duration
        }
        val warmAvg = warmTimes.fold(Duration.ZERO) { acc, d -> acc + d } / warmRuns
        val warmMin = warmTimes.min()
        val warmMax = warmTimes.max()
        println("SDS warm parse over $warmRuns runs: avg=$warmAvg min=$warmMin max=$warmMax")

        // Sanity — the captured SDS has at least the well-known core services.
        val names = cold.serviceInformation.service.map { it.name }.toSet()
        assertTrue(ServiceNames.EventService in names) { "missing EventService: $names" }
        assertTrue(ServiceNames.CardService in names) { "missing CardService: $names" }
        assertTrue(cold.serviceInformation.service.size >= 10) {
            "expected ≥10 services in real SDS, got ${cold.serviceInformation.service.size}"
        }

        // Regression guard. Real Konnektor SDS parses in <50ms warm locally; 250ms gives
        // headroom for slow CI without hiding a 5× slowdown.
        assertTrue(warmAvg.inWholeMilliseconds < 250) {
            "SDS warm parse averaged ${warmAvg.inWholeMilliseconds}ms — > 250ms suggests a regression"
        }
    }
}
