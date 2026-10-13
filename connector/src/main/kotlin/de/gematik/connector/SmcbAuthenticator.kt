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
package de.gematik.connector

/**
 * The two card operations behind SMC-B token signing: read the C.AUT certificate and sign a
 * digest with its key. A Konnektor ([ConnectorClient]) and a Basis-Consumer ([ConsumerClient])
 * both offer them, through different services and namespaces.
 */
interface SmcbAuthenticator {
    /** DER bytes of the ECC C.AUT certificate of the identity behind [cardHandle]. */
    suspend fun readCardAutCertificate(cardHandle: String): ByteArray

    /** DER-encoded ECDSA signature over [hash] (a SHA-256 digest) with the C.AUT key. */
    suspend fun externalAuthenticate(cardHandle: String, hash: ByteArray): ByteArray
}
