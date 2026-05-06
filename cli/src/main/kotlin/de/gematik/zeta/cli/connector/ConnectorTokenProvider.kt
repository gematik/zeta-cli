package de.gematik.zeta.cli.connector

import de.gematik.connector.ConnectorClient
import de.gematik.zeta.sdk.authentication.smcb.ConnectorApi
import de.gematik.zeta.sdk.authentication.smcb.model.ExternalAuthenticateResponse
import de.gematik.zeta.sdk.authentication.smcb.model.ReadCardCertificateResponse
import de.gematik.zeta.sdk.authentication.smcb.model.SignatureObject
import de.gematik.zeta.sdk.authentication.smcb.model.Status
import de.gematik.zeta.sdk.authentication.smcb.model.X509Data
import de.gematik.zeta.sdk.authentication.smcb.model.X509DataInfo
import de.gematik.zeta.sdk.authentication.smcb.model.X509DataInfoList
import de.gematik.zeta.sdk.authentication.smcb.model.X509IssuerSerial
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * SDK-side `ConnectorApi` that delegates to a [ConnectorClient].
 *
 * The Zeta SDK's `SmcbTokenProvider` calls `readCertificate` to obtain the C.AUT cert
 * and `externalAuthenticate` to sign the JWT digest with the card. Both round-trip via
 * the same Connector service the connector module already speaks; this adapter is the
 * glue that wraps our `ByteArray` returns into the SDK's response shape.
 *
 * The interface methods receive `mandantId` / `clientSystemId` / `workspaceId` / `userId`
 * because the SDK reads them from its own `ConnectorConfig`, but our [ConnectorClient]
 * already carries the same values via its `ConnectorContext` (built from the active
 * `.kon`), so we don't thread them through. `cardHandle` is taken from the SDK call so
 * the adapter is stateless beyond the [ConnectorClient] handle.
 */
@OptIn(ExperimentalEncodingApi::class)
class ConnectorTokenProvider(
    private val connector: ConnectorClient,
) : ConnectorApi {
    @Suppress("UNUSED_PARAMETER")
    override suspend fun readCertificate(
        cardHandle: String,
        mandantId: String,
        clientSystemId: String?,
        workspaceId: String?,
        userId: String?,
    ): ReadCardCertificateResponse {
        val certBytes = connector.readCardAutCertificate(cardHandle)
        val b64 = Base64.encode(certBytes)
        return ReadCardCertificateResponse(
            status = Status(result = "OK"),
            x509DataInfoList =
                X509DataInfoList(
                    x509DataInfo =
                        listOf(
                            X509DataInfo(
                                certRef = "C.AUT",
                                // The SDK only reads x509Certificate downstream; the rest are
                                // required by the data class but not consumed.
                                x509Data =
                                    X509Data(
                                        x509IssuerSerial = X509IssuerSerial("", ""),
                                        x509SubjectName = "",
                                        x509Certificate = b64,
                                    ),
                            ),
                        ),
                ),
        )
    }

    @Suppress("UNUSED_PARAMETER")
    override suspend fun externalAuthenticate(
        cardHandle: String,
        mandantId: String,
        clientSystemId: String?,
        workspaceId: String?,
        userId: String?,
        base64Challenge: String,
    ): ExternalAuthenticateResponse {
        // decode base64 without padding
        val hash = Base64.Mime.withPadding(Base64.PaddingOption.ABSENT).decode(base64Challenge)
        val sigBytes = connector.externalAuthenticate(cardHandle, hash)
        val b64 = Base64.encode(sigBytes)
        return ExternalAuthenticateResponse(
            status = Status(result = "OK"),
            signatureObject = SignatureObject(base64Signature = b64),
        )
    }
}
