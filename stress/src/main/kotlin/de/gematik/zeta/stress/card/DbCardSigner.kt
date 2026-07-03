package de.gematik.zeta.stress.card

import de.gematik.zeta.sdk.authentication.smcb.CustomConnectorApi
import de.gematik.zeta.stress.db.Card
import de.gematik.zeta.stress.crypto.BC_PROVIDER
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Signs SMC-B subject tokens straight from the card blobs held in the DB — no PKCS#12 detour.
 *
 * Plugged into [de.gematik.zeta.sdk.authentication.smcb.CustomSmcbTokenProvider], which is the
 * SDK's official "external signer" seam (the same one the Konnektor path uses). The SDK's
 * `BaseSmcbTokenProvider` does all the JWT assembly and calls back here for exactly two things:
 *
 * - [readCertificate] → the DER-encoded X.509 AUT certificate (returned verbatim from the DB).
 * - [externalAuthenticate] → given `base64(SHA-256(token))`, return a **DER-encoded ECDSA**
 *   signature over that digest. This mirrors a Konnektor's ExternalAuthenticate: the input is
 *   already hashed, so we sign it with `NONEwithECDSA`, and the SDK converts the DER result to
 *   JOSE R‖S itself.
 *
 * gematik SMC-B AUT keys are **brainpoolP256r1** (the "E256" in the filenames), which the JDK's
 * SunEC provider does not support — hence [BC_PROVIDER] (BouncyCastle) for both key parsing and
 * signing. The parsed [PrivateKey] is cached; `createSubjectToken` runs at most once per client
 * per cold auth, but caching keeps repeated storms cheap.
 */
class DbCardSigner(private val card: Card) : CustomConnectorApi {

    private val privateKey: PrivateKey by lazy {
        KeyFactory.getInstance("EC", BC_PROVIDER).generatePrivate(PKCS8EncodedKeySpec(card.privKey))
    }

    override suspend fun readCertificate(): ByteArray = card.cert

    override suspend fun externalAuthenticate(base64Challenge: String): ByteArray {
        val digest = Base64.getDecoder().decode(base64Challenge)
        return Signature.getInstance("NONEwithECDSA", BC_PROVIDER).run {
            initSign(privateKey)
            update(digest)
            sign()
        }
    }
}
