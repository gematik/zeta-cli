/*
 * Copyright (Change Date see Readme), gematik GmbH
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the
 * European Commission – subsequent versions of the EUPL (the "Licence").
 * You may not use this work except in compliance with the Licence.
 *
 * You find a copy of the Licence in the "Licence" file or at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.
 * In case of changes by gematik GmbH find details in the "Readme" file.
 *
 * See the Licence for the specific language governing permissions and limitations under the Licence.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 */
package de.gematik.zeta.cli.connector

import de.gematik.connector.SmcbAuthenticator
import de.gematik.zeta.sdk.authentication.smcb.CustomConnectorApi
import java.util.Base64

/**
 * [CustomConnectorApi] that signs SMC-B subject tokens via a Konnektor or a Basis-Consumer, whichever
 * [SmcbAuthenticator] the active `.kon` describes.
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
 * already carried by the Konnektor client (built from the active `.kon`), so it isn't threaded here.
 */
class ConnectorTokenProvider(
    private val connector: SmcbAuthenticator,
    private val cardHandle: String,
) : CustomConnectorApi {

    override suspend fun readCertificate(): ByteArray = connector.readCardAutCertificate(cardHandle)

    override suspend fun externalAuthenticate(base64Challenge: String): ByteArray {
        val digest = Base64.getDecoder().decode(base64Challenge)
        return connector.externalAuthenticate(cardHandle, digest)
    }
}
