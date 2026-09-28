package de.gematik.connector

import io.ktor.client.HttpClient

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

/**
 * The [SmcbAuthenticator] for this `.kon`'s [Dotkon.product]. A Konnektor loads its service
 * directory first; a Basis-Consumer has none, so building one does no I/O.
 */
suspend fun Dotkon.smcbAuthenticator(httpClient: HttpClient): SmcbAuthenticator =
    when (product) {
        Product.Konnektor -> ConnectorClient.connect(httpClient, this)
        Product.Consumer -> ConsumerClient(httpClient, this)
    }
