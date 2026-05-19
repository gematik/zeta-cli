package de.gematik.connector

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlin.time.measureTimedValue
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

private val log = KotlinLogging.logger {}

// The wrapper element lives in the ServiceDirectory namespace; its two main children
// (ProductInformation, ServiceInformation) sit in their own namespaces — observed on
// real Connectors. Don't fold them into one constant.
private const val SDS_NS = "http://ws.gematik.de/conn/ServiceDirectory/v3.1"
private const val PRODUCT_INFO_NS = "http://ws.gematik.de/int/version/ProductInformation/v1.1"
private const val SERVICE_INFO_NS = "http://ws.gematik.de/conn/ServiceInformation/v2.0"
internal const val SDS_PATH = "connector.sds"

/**
 * Service Directory Service: the contents of `<base>/connector.sds` parsed.
 *
 * The Connector advertises which SOAP services it implements, in which versions, and at
 * which endpoint URLs. Discover at startup, never hardcode endpoints.
 */
@Serializable
@XmlSerialName("ConnectorServices", namespace = SDS_NS)
data class ConnectorServices(
    @XmlElement(true)
    @XmlSerialName("ProductInformation", namespace = PRODUCT_INFO_NS)
    val productInformation: ProductInformation,
    @XmlElement(true)
    @XmlSerialName("ServiceInformation", namespace = SERVICE_INFO_NS)
    val serviceInformation: ServiceInformation,
)

@Serializable
@XmlSerialName("ProductInformation", namespace = PRODUCT_INFO_NS)
data class ProductInformation(
    @XmlElement(true)
    @XmlSerialName("ProductTypeInformation", namespace = PRODUCT_INFO_NS)
    val productTypeInformation: ProductTypeInformation,
    @XmlElement(true)
    @XmlSerialName("ProductIdentification", namespace = PRODUCT_INFO_NS)
    val productIdentification: ProductIdentification,
)

@Serializable
@XmlSerialName("ProductTypeInformation", namespace = PRODUCT_INFO_NS)
data class ProductTypeInformation(
    @XmlElement(true)
    @XmlSerialName("ProductType", namespace = PRODUCT_INFO_NS)
    val productType: String,
    @XmlElement(true)
    @XmlSerialName("ProductTypeVersion", namespace = PRODUCT_INFO_NS)
    val productTypeVersion: String,
)

@Serializable
@XmlSerialName("ProductIdentification", namespace = PRODUCT_INFO_NS)
data class ProductIdentification(
    @XmlElement(true)
    @XmlSerialName("ProductVendorID", namespace = PRODUCT_INFO_NS)
    val productVendorId: String,
    @XmlElement(true)
    @XmlSerialName("ProductCode", namespace = PRODUCT_INFO_NS)
    val productCode: String,
    @XmlElement(true)
    @XmlSerialName("ProductVersion", namespace = PRODUCT_INFO_NS)
    val productVersion: ProductVersion,
)

@Serializable
@XmlSerialName("ProductVersion", namespace = PRODUCT_INFO_NS)
data class ProductVersion(
    @XmlElement(true)
    @XmlSerialName("Local", namespace = PRODUCT_INFO_NS)
    val local: LocalProductVersion,
)

@Serializable
@XmlSerialName("Local", namespace = PRODUCT_INFO_NS)
data class LocalProductVersion(
    @XmlElement(true)
    @XmlSerialName("HWVersion", namespace = PRODUCT_INFO_NS)
    val hwVersion: String,
    @XmlElement(true)
    @XmlSerialName("FWVersion", namespace = PRODUCT_INFO_NS)
    val fwVersion: String,
)

@Serializable
@XmlSerialName("ServiceInformation", namespace = SERVICE_INFO_NS)
data class ServiceInformation(
    @XmlElement(true)
    @XmlSerialName("Service", namespace = SERVICE_INFO_NS)
    val service: List<Service> = emptyList(),
)

@Serializable
@XmlSerialName("Service", namespace = SERVICE_INFO_NS)
data class Service(
    @XmlElement(false)
    @XmlSerialName("Name")
    val name: String,
    @XmlElement(true)
    @XmlSerialName("Abstract", namespace = SERVICE_INFO_NS)
    val abstract: String = "",
    @XmlElement(true)
    @XmlSerialName("Versions", namespace = SERVICE_INFO_NS)
    val versions: Versions = Versions(),
)

@Serializable
@XmlSerialName("Versions", namespace = SERVICE_INFO_NS)
data class Versions(
    @XmlElement(true)
    @XmlSerialName("Version", namespace = SERVICE_INFO_NS)
    val version: List<ServiceVersion> = emptyList(),
)

@Serializable
@XmlSerialName("Version", namespace = SERVICE_INFO_NS)
data class ServiceVersion(
    @XmlElement(false)
    @XmlSerialName("TargetNamespace")
    val targetNamespace: String,
    @XmlElement(false)
    @XmlSerialName("Version")
    val version: String,
    @XmlElement(true)
    @XmlSerialName("Abstract", namespace = SERVICE_INFO_NS)
    val abstract: String = "",
    @XmlElement(true)
    @XmlSerialName("EndpointTLS", namespace = SERVICE_INFO_NS)
    val endpointTLS: ServiceEndpoint? = null,
    @XmlElement(true)
    @XmlSerialName("Endpoint", namespace = SERVICE_INFO_NS)
    val endpoint: ServiceEndpoint? = null,
)

@Serializable
data class ServiceEndpoint(
    @XmlElement(false)
    @XmlSerialName("Location")
    val location: String,
)

/** Common Connector service names — match the `Name` attribute on each `<Service>`. */
object ServiceNames {
    const val CardService = "CardService"
    const val EventService = "EventService"
    const val AuthSignatureService = "AuthSignatureService"
    const val CardTerminalService = "CardTerminalService"
    const val CertificateService = "CertificateService"
    const val SignatureService = "SignatureService"
    const val EncryptionService = "EncryptionService"
}

/**
 * Replace scheme + host (+ port) of every TLS / non-TLS endpoint with the corresponding
 * components of [baseUrl], preserving the original path. Used when the Connector
 * advertises internal hostnames that aren't reachable from the client.
 */
fun ConnectorServices.withRewrittenEndpoints(baseUrl: String): ConnectorServices {
    val base = Url(baseUrl)
    return copy(
        serviceInformation = serviceInformation.copy(
            service = serviceInformation.service.map { svc ->
                svc.copy(
                    versions = svc.versions.copy(
                        version = svc.versions.version.map { ver ->
                            ver.copy(
                                endpointTLS = ver.endpointTLS?.let { it.copy(location = rewriteLocation(it.location, base)) },
                                endpoint = ver.endpoint?.let { it.copy(location = rewriteLocation(it.location, base)) },
                            )
                        },
                    ),
                )
            },
        ),
    )
}

private fun rewriteLocation(location: String, base: Url): String =
    URLBuilder(location).apply {
        protocol = base.protocol
        host = base.host
        port = base.port
    }.buildString()

/**
 * Fetch and parse `<baseUrl>/connector.sds` using [httpClient]. The caller owns and
 * configures the HTTP client (TLS / auth) — this function performs a plain GET with
 * `Accept: text/xml, application/xml`.
 */
suspend fun loadConnectorServices(httpClient: HttpClient, baseUrl: String): ConnectorServices {
    val sdsUrl = "${baseUrl.trimEnd('/')}/$SDS_PATH"
    log.debug { "Loading service directory from $sdsUrl" }
    val (response, fetchTime) = measureTimedValue {
        httpClient.get(sdsUrl) {
            accept(ContentType.Text.Xml)
            accept(ContentType.Application.Xml)
        }
    }
    log.debug { "SDS GET $sdsUrl took $fetchTime (status ${response.status.value})" }
    if (!response.status.isSuccess()) {
        // Body is logged at -vv via the wire logger; keep this message single-line.
        throw ConnectorServicesException(
            "Unexpected HTTP ${response.status.value} ${response.status.description} fetching $sdsUrl",
        )
    }
    val (body, readTime) = measureTimedValue { response.bodyAsText() }
    log.debug { "SDS body read (${body.length} chars) took $readTime" }
    return try {
        val (services, parseTime) = measureTimedValue {
            defaultXml.decodeFromString(ConnectorServices.serializer(), body)
        }
        log.debug { "SDS XML parse took $parseTime (${services.serviceInformation.service.size} services)" }
        services
    } catch (e: Exception) {
        throw ConnectorServicesException("Could not parse SDS at $sdsUrl: ${e.message}", e)
    }
}

/**
 * Convert a `M.m.p` semver to an int suitable for ranking. Non-conforming strings rank
 * as 0, matching the Go behaviour where parse failures fall to the bottom.
 */
internal fun semverAsNumber(version: String): Int {
    val match = Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(version) ?: return 0
    val (major, minor, patch) = match.destructured
    return major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
}

internal val defaultXml: XML = XML {
    autoPolymorphic = false
    defaultPolicy {
        ignoreUnknownChildren()
    }
}

class ConnectorServicesException(message: String, cause: Throwable? = null) :
    ConnectorException(message, cause)
