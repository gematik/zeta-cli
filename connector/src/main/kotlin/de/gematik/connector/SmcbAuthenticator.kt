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
