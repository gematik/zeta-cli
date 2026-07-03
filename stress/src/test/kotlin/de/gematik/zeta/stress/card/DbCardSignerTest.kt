package de.gematik.zeta.stress.card

import de.gematik.zeta.stress.db.Card
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DbCardSignerTest {

    @Test
    fun `externalAuthenticate produces a DER ECDSA signature over the challenge digest`() = runBlocking {
        val kp = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

        // The SDK passes base64(SHA-256(token)); emulate with an arbitrary 32-byte digest.
        val digest = ByteArray(32) { it.toByte() }
        val challenge = Base64.getEncoder().withoutPadding().encodeToString(digest)

        val card = Card("test", cert = byteArrayOf(1, 2, 3), privKey = kp.private.encoded)
        val signer = DbCardSigner(card)

        assertArrayEquals(byteArrayOf(1, 2, 3), signer.readCertificate())

        val sig = signer.externalAuthenticate(challenge)

        // Verify with NONEwithECDSA over the same digest — this is what derEcdsaToJose consumes.
        val ok = Signature.getInstance("NONEwithECDSA").run {
            initVerify(kp.public)
            update(digest)
            verify(sig)
        }
        assertTrue(ok, "signature must verify against the card public key")
    }
}
