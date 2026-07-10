package de.gematik.zeta.cli.connector

import de.gematik.connector.ConnectorClient
import de.gematik.zeta.sdk.authentication.smcb.CustomConnectorApi
import java.util.Base64

/**
 * [CustomConnectorApi] that signs SMC-B subject tokens via the Konnektor behind [ConnectorClient].
 *
 * zeta-sdk's [de.gematik.zeta.sdk.authentication.smcb.CustomSmcbTokenProvider] assembles the JWT and
 * calls back here for exactly two things — the same external-signer seam the DB path uses
 * ([de.gematik.zeta.stress.identity.DbCardSigner]):
 *
 * - [readCertificate] → the DER-encoded C.AUT X.509 certificate (verbatim from the card).
 * - [externalAuthenticate] → given `base64(SHA-256(token))`, a **DER-encoded ECDSA** signature over
 *   that digest, which is exactly what the Konnektor's ExternalAuthenticate returns. The SDK converts
 *   the DER result to the JOSE R‖S form itself.
 *
 * The [cardHandle] is resolved once up front; mandant / client-system / workspace / user context is
 * already carried by the [ConnectorClient] (built from the active `.kon`), so it isn't threaded here.
 */
class ConnectorTokenProvider(
    private val connector: ConnectorClient,
    private val cardHandle: String,
) : CustomConnectorApi {

    override suspend fun readCertificate(): ByteArray = connector.readCardAutCertificate(cardHandle)

    override suspend fun externalAuthenticate(base64Challenge: String): ByteArray {
        val digest = Base64.getDecoder().decode(base64Challenge)
        return connector.externalAuthenticate(cardHandle, digest)
    }
}
