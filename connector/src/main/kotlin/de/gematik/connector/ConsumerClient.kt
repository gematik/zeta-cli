package de.gematik.connector

import de.gematik.connector.api.gematik.consumer.certificateservice30.ReadCertificateEnvelope
import de.gematik.connector.api.gematik.consumer.certificateservice30.ReadCertificateResponseEnvelope
import de.gematik.connector.api.gematik.consumer.certificateservice31.CryptType
import de.gematik.connector.api.gematik.consumer.certificateservice31.ReadCertificate
import de.gematik.connector.api.gematik.consumer.certificateservice31.ReadCertificateCertRefList
import de.gematik.connector.api.gematik.consumer.signatureservice32.BinaryString
import de.gematik.connector.api.gematik.consumer.signatureservice32.ExternalAuthenticate
import de.gematik.connector.api.gematik.consumer.signatureservice32.ExternalAuthenticateEnvelope
import de.gematik.connector.api.gematik.consumer.signatureservice32.ExternalAuthenticateOptionalInputs
import de.gematik.connector.api.gematik.consumer.signatureservice32.ExternalAuthenticateResponseEnvelope
import de.gematik.connector.api.oasis.dss10core.Base64Data
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import de.gematik.connector.api.gematik.consumer.certificateservice30.Operations as CertificateServiceOperations
import de.gematik.connector.api.gematik.consumer.signatureservice32.Operations as SignatureServiceOperations

private val log = KotlinLogging.logger {}

/**
 * SMC-B signing through a gematik Basis-Consumer (gemSpec_Basis_Consumer, see
 * `docs/basis-consumer.md`).
 *
 * Unlike a Konnektor there is no service directory to load and no call context to send: each
 * operation goes to the path the `.kon` names in `serviceEndpoints` and carries only the card
 * handle. Faults surface exactly as they do for [ConnectorClient], through [ServiceProxy].
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
        log.debug { "ReadCertificate cardHandle=$cardHandle certRef=C.AUT crypt=ECC" }
        val envelope =
            ReadCertificateEnvelope(
                body =
                    ReadCertificateEnvelope.Body(
                        readCertificate =
                            ReadCertificate(
                                cardHandle = cardHandle,
                                certRefList = ReadCertificateCertRefList(certRef = listOf("C.AUT")),
                                crypt = CryptType.Ecc,
                            ),
                    ),
            )
        val resp: ReadCertificateResponseEnvelope =
            proxy(ServiceNames.CertificateService, CERTIFICATE_SERVICE_NS, CERTIFICATE_SERVICE_VERSION).call(
                CertificateServiceOperations.ReadCertificate,
                envelope,
                label = "ReadCertificate(cardHandle=$cardHandle)",
            )
        val info =
            resp.body.readCertificateResponse
                ?.x509DataInfoList
                ?.x509DataInfo
                ?.firstOrNull()
                ?: error("ReadCertificate: empty X509DataInfoList")
        val b64 =
            info.x509Data?.x509Certificate
                ?: error("ReadCertificate: no X509Certificate in response (certRef=${info.certRef})")
        return Base64.Mime.decode(b64)
    }

    /**
     * `SignatureService.ExternalAuthenticate` with an explicit ECDSA signature type, which the
     * Basis-Consumer accepts whether or not its "ECC preferred" switch is on.
     */
    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun externalAuthenticate(cardHandle: String, hash: ByteArray): ByteArray {
        log.debug { "ExternalAuthenticate cardHandle=$cardHandle" }
        val envelope =
            ExternalAuthenticateEnvelope(
                body =
                    ExternalAuthenticateEnvelope.Body(
                        externalAuthenticate =
                            ExternalAuthenticate(
                                cardHandle = cardHandle,
                                optionalInputs = ExternalAuthenticateOptionalInputs(signatureType = ECDSA_SIGNATURE_TYPE),
                                binaryString =
                                    BinaryString(
                                        base64Data =
                                            Base64Data(charData = Base64.Mime.encode(hash), mimeType = "application/octet-stream"),
                                    ),
                            ),
                    ),
            )
        val resp: ExternalAuthenticateResponseEnvelope =
            proxy(ServiceNames.SignatureService, SIGNATURE_SERVICE_NS, SIGNATURE_SERVICE_VERSION).call(
                SignatureServiceOperations.ExternalAuthenticate,
                envelope,
                label = "ExternalAuthenticate(cardHandle=$cardHandle)",
            )
        val sig =
            resp.body.externalAuthenticateResponse
                ?.signatureObject
                ?.base64Signature
                ?.charData
                ?: error("ExternalAuthenticate: no Base64Signature in response")
        return Base64.Mime.decode(sig)
    }

    // ServiceProxy only uses the service / version for its messages; a consumer has no service
    // directory to take them from, so they describe the WSDL the types were generated from.
    private fun proxy(name: String, targetNamespace: String, version: String): ServiceProxy {
        val endpoint = dotkon.consumerEndpoint(name)
        val serviceVersion = ServiceVersion(
            targetNamespace = targetNamespace,
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

    private companion object {
        const val CERTIFICATE_SERVICE_NS = "http://ws.gematik.de/consumer/CertificateService/WSDL/v3.0"
        const val CERTIFICATE_SERVICE_VERSION = "3.0.1"
        const val SIGNATURE_SERVICE_NS = "http://ws.gematik.de/consumer/SignatureService/WSDL/v3.2"
        const val SIGNATURE_SERVICE_VERSION = "3.2.1"
    }
}
