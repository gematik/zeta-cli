package de.gematik.connector

import de.gematik.connector.api.oasis.dss10core.Base64Data
import de.gematik.connector.api.gematik.consumer.certificateservice30.Operations as OperationsV301
import de.gematik.connector.api.gematik.consumer.certificateservice30.ReadCertificateEnvelope as ReadCertificateEnvelopeV301
import de.gematik.connector.api.gematik.consumer.certificateservice30.ReadCertificateResponseEnvelope as ReadCertificateResponseEnvelopeV301
import de.gematik.connector.api.gematik.consumer.certificateservice300.CryptType as CryptTypeV300
import de.gematik.connector.api.gematik.consumer.certificateservice300.Operations as OperationsV300
import de.gematik.connector.api.gematik.consumer.certificateservice300.ReadCertificate as ReadCertificateV300
import de.gematik.connector.api.gematik.consumer.certificateservice300.ReadCertificateCertRefList as ReadCertificateCertRefListV300
import de.gematik.connector.api.gematik.consumer.certificateservice300.ReadCertificateEnvelope as ReadCertificateEnvelopeV300
import de.gematik.connector.api.gematik.consumer.certificateservice300.ReadCertificateResponseEnvelope as ReadCertificateResponseEnvelopeV300
import de.gematik.connector.api.gematik.consumer.certificateservice31.CryptType as CryptTypeV301
import de.gematik.connector.api.gematik.consumer.certificateservice31.ReadCertificate as ReadCertificateV301
import de.gematik.connector.api.gematik.consumer.certificateservice31.ReadCertificateCertRefList as ReadCertificateCertRefListV301
import de.gematik.connector.api.gematik.consumer.signatureservice30.BinaryString as BinaryStringV30
import de.gematik.connector.api.gematik.consumer.signatureservice30.ExternalAuthenticate as ExternalAuthenticateV30
import de.gematik.connector.api.gematik.consumer.signatureservice30.ExternalAuthenticateEnvelope as ExternalAuthenticateEnvelopeV30
import de.gematik.connector.api.gematik.consumer.signatureservice30.ExternalAuthenticateOptionalInputs as ExternalAuthenticateOptionalInputsV30
import de.gematik.connector.api.gematik.consumer.signatureservice30.ExternalAuthenticateResponseEnvelope as ExternalAuthenticateResponseEnvelopeV30
import de.gematik.connector.api.gematik.consumer.signatureservice30.Operations as OperationsV30
import de.gematik.connector.api.gematik.consumer.signatureservice31.BinaryString as BinaryStringV31
import de.gematik.connector.api.gematik.consumer.signatureservice31.ExternalAuthenticate as ExternalAuthenticateV31
import de.gematik.connector.api.gematik.consumer.signatureservice31.ExternalAuthenticateEnvelope as ExternalAuthenticateEnvelopeV31
import de.gematik.connector.api.gematik.consumer.signatureservice31.ExternalAuthenticateResponseEnvelope as ExternalAuthenticateResponseEnvelopeV31
import de.gematik.connector.api.gematik.consumer.signatureservice31.Operations as OperationsV31
import de.gematik.connector.api.gematik.consumer.signatureservice32.BinaryString as BinaryStringV32
import de.gematik.connector.api.gematik.consumer.signatureservice32.ExternalAuthenticate as ExternalAuthenticateV32
import de.gematik.connector.api.gematik.consumer.signatureservice32.ExternalAuthenticateEnvelope as ExternalAuthenticateEnvelopeV32
import de.gematik.connector.api.gematik.consumer.signatureservice32.ExternalAuthenticateOptionalInputs as ExternalAuthenticateOptionalInputsV32
import de.gematik.connector.api.gematik.consumer.signatureservice32.ExternalAuthenticateResponseEnvelope as ExternalAuthenticateResponseEnvelopeV32
import de.gematik.connector.api.gematik.consumer.signatureservice32.Operations as OperationsV32
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private val log = KotlinLogging.logger {}

/**
 * WSDL versions [ConsumerClient] can speak, per service. Each is a separate XML namespace, and the
 * `.kon` names the one its Basis-Consumer serves.
 *
 * - CertificateService 3.0.0 (Consumer 1.0/1.1, schema v3.0) and 3.0.1 (Consumer 1.2, schema v3.1).
 * - SignatureService 3.0.0, 3.1.0, 3.2.0 and 3.2.1. 3.1.0 and 3.2.0 dropped `OptionalInputs` from
 *   ExternalAuthenticate, 3.2.1 brought it back.
 */
internal val CONSUMER_SERVICE_VERSIONS: Map<String, List<String>> = mapOf(
    ServiceNames.CertificateService to listOf("3.0.0", "3.0.1"),
    ServiceNames.SignatureService to listOf("3.0.0", "3.1.0", "3.2.0", "3.2.1"),
)

/**
 * SMC-B signing through a gematik Basis-Consumer (gemSpec_Basis_Consumer, see
 * `docs/basis-consumer.md`).
 *
 * Unlike a Konnektor there is no service directory to load and no call context to send: each
 * operation goes to the endpoint the `.kon` names in `serviceEndpoints`, in the version it names
 * there, and carries only the card handle. Faults surface exactly as they do for
 * [ConnectorClient], through [ServiceProxy].
 */
class ConsumerClient(
    val httpClient: HttpClient,
    val dotkon: Dotkon,
) : SmcbAuthenticator {

    /**
     * `CertificateService.ReadCertificate` for `C.AUT` / `ECC`. The DER bytes of the first
     * certificate returned.
     */
    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun readCardAutCertificate(cardHandle: String): ByteArray {
        val service = ServiceNames.CertificateService
        val version = versionOf(service)
        log.debug { "ReadCertificate $version cardHandle=$cardHandle certRef=C.AUT crypt=ECC" }
        val label = "ReadCertificate(cardHandle=$cardHandle)"
        val b64 = when (version) {
            "3.0.0" -> {
                val resp: ReadCertificateResponseEnvelopeV300 = proxy(service, version).call(
                    OperationsV300.ReadCertificate,
                    ReadCertificateEnvelopeV300(
                        ReadCertificateEnvelopeV300.Body(
                            ReadCertificateV300(
                                cardHandle = cardHandle,
                                certRefList = ReadCertificateCertRefListV300(certRef = listOf("C.AUT")),
                                crypt = CryptTypeV300.Ecc,
                            ),
                        ),
                    ),
                    label,
                )
                resp.body.readCertificateResponse?.x509DataInfoList?.x509DataInfo?.firstOrNull()?.x509Data?.x509Certificate
            }
            else -> {
                val resp: ReadCertificateResponseEnvelopeV301 = proxy(service, version).call(
                    OperationsV301.ReadCertificate,
                    ReadCertificateEnvelopeV301(
                        ReadCertificateEnvelopeV301.Body(
                            ReadCertificateV301(
                                cardHandle = cardHandle,
                                certRefList = ReadCertificateCertRefListV301(certRef = listOf("C.AUT")),
                                crypt = CryptTypeV301.Ecc,
                            ),
                        ),
                    ),
                    label,
                )
                resp.body.readCertificateResponse?.x509DataInfoList?.x509DataInfo?.firstOrNull()?.x509Data?.x509Certificate
            }
        } ?: error("ReadCertificate: no X509Certificate in response")
        return Base64.Mime.decode(b64)
    }

    /**
     * `SignatureService.ExternalAuthenticate` over a SHA-256 digest. Where the version's schema has
     * `OptionalInputs` the ECDSA signature type is named explicitly; 3.1.0 and 3.2.0 have no such
     * element and sign ECDSA by default (A_17578-03 / -04).
     */
    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun externalAuthenticate(cardHandle: String, hash: ByteArray): ByteArray {
        val service = ServiceNames.SignatureService
        val version = versionOf(service)
        log.debug { "ExternalAuthenticate $version cardHandle=$cardHandle" }
        val label = "ExternalAuthenticate(cardHandle=$cardHandle)"
        val data = Base64Data(charData = Base64.Mime.encode(hash), mimeType = "application/octet-stream")
        val sig = when (version) {
            "3.0.0" -> {
                val resp: ExternalAuthenticateResponseEnvelopeV30 = proxy(service, version).call(
                    OperationsV30.ExternalAuthenticate,
                    ExternalAuthenticateEnvelopeV30(
                        ExternalAuthenticateEnvelopeV30.Body(
                            ExternalAuthenticateV30(
                                cardHandle = cardHandle,
                                optionalInputs = ExternalAuthenticateOptionalInputsV30(signatureType = ECDSA_SIGNATURE_TYPE),
                                binaryString = BinaryStringV30(base64Data = data),
                            ),
                        ),
                    ),
                    label,
                )
                resp.body.externalAuthenticateResponse?.signatureObject?.base64Signature?.charData
            }
            "3.1.0" -> {
                val resp: ExternalAuthenticateResponseEnvelopeV31 = proxy(service, version).call(
                    OperationsV31.ExternalAuthenticate,
                    ExternalAuthenticateEnvelopeV31(
                        ExternalAuthenticateEnvelopeV31.Body(
                            ExternalAuthenticateV31(cardHandle = cardHandle, binaryString = BinaryStringV31(base64Data = data)),
                        ),
                    ),
                    label,
                )
                resp.body.externalAuthenticateResponse?.signatureObject?.base64Signature?.charData
            }
            else -> {
                val resp: ExternalAuthenticateResponseEnvelopeV32 = proxy(service, version).call(
                    OperationsV32.ExternalAuthenticate,
                    ExternalAuthenticateEnvelopeV32(
                        ExternalAuthenticateEnvelopeV32.Body(
                            ExternalAuthenticateV32(
                                cardHandle = cardHandle,
                                // 3.2.0 and 3.2.1 share the namespace; only 3.2.1 has OptionalInputs.
                                optionalInputs = if (version == "3.2.0") {
                                    null
                                } else {
                                    ExternalAuthenticateOptionalInputsV32(signatureType = ECDSA_SIGNATURE_TYPE)
                                },
                                binaryString = BinaryStringV32(base64Data = data),
                            ),
                        ),
                    ),
                    label,
                )
                resp.body.externalAuthenticateResponse?.signatureObject?.base64Signature?.charData
            }
        } ?: error("ExternalAuthenticate: no Base64Signature in response")
        return Base64.Mime.decode(sig)
    }

    private fun versionOf(service: String): String {
        val version = dotkon.serviceEndpoints.firstOrNull { it.name == service }?.version
            ?: throw DotkonValidationException(listOf("serviceEndpoints has no entry named \"$service\""))
        if (version !in CONSUMER_SERVICE_VERSIONS.getValue(service)) {
            throw DotkonValidationException(listOf("$service version \"$version\" is not supported"))
        }
        return version
    }

    // ServiceProxy only uses the service / version for its messages; a consumer has no service
    // directory to take them from, so they describe the configured endpoint.
    private fun proxy(name: String, version: String): ServiceProxy {
        val endpoint = dotkon.consumerEndpoint(name)
        val serviceVersion = ServiceVersion(
            targetNamespace = "",
            version = version,
            endpointTLS = ServiceEndpoint(endpoint),
        )
        return ServiceProxy(
            httpClient = httpClient,
            endpoint = endpoint,
            service = Service(name = name, versions = Versions(listOf(serviceVersion))),
            serviceVersion = serviceVersion,
        )
    }
}
