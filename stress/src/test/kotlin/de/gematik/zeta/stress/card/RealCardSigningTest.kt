package de.gematik.zeta.stress.card

import de.gematik.zeta.stress.crypto.BC_PROVIDER
import de.gematik.zeta.stress.db.Card
import java.io.ByteArrayInputStream
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises the DB-direct signer against a real gematik test SMC-B (DER cert + PKCS#8 EC key
 * lifted from the corpus). Proves the actual card material parses and that the signature the SDK
 * would receive verifies against the card's own certificate — the correctness hinge of the
 * whole harness.
 */
class RealCardSigningTest {

    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream("/card/$name")!!.use { it.readBytes() }

    @Test
    fun `signs a challenge that verifies against the real card certificate`() = runBlocking {
        val certDer = resource("smcb.crt")
        val keyDer = resource("smcb.prv")
        val card = Card("80276883110001000000", cert = certDer, privKey = keyDer)

        val cert = CertificateFactory.getInstance("X.509", BC_PROVIDER)
            .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate

        val signer = DbCardSigner(card)

        val digest = ByteArray(32) { (it * 7).toByte() }
        val challenge = Base64.getEncoder().withoutPadding().encodeToString(digest)

        val sig = signer.externalAuthenticate(challenge)

        val ok = Signature.getInstance("NONEwithECDSA", BC_PROVIDER).run {
            initVerify(cert.publicKey)
            update(digest)
            verify(sig)
        }
        assertTrue(ok, "signature must verify against the card's certificate public key")
    }
}
