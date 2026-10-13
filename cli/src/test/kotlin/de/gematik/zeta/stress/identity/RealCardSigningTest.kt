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
package de.gematik.zeta.stress.identity

import de.gematik.zeta.stress.crypto.BC_PROVIDER
import de.gematik.zeta.stress.db.Identity
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
 * lifted from the corpus). Proves the actual identity material parses and that the signature the SDK
 * would receive verifies against the identity's own certificate — the correctness hinge of the
 * whole harness.
 */
class RealCardSigningTest {

    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream("/identity/$name")!!.use { it.readBytes() }

    @Test
    fun `signs a challenge that verifies against the real identity certificate`() = runBlocking {
        val certDer = resource("smcb.crt")
        val keyDer = resource("smcb.prv")
        val identity = Identity("80276883110001000000", cert = certDer, privKey = keyDer)

        val cert = CertificateFactory.getInstance("X.509", BC_PROVIDER)
            .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate

        val signer = DbCardSigner(identity)

        val digest = ByteArray(32) { (it * 7).toByte() }
        val challenge = Base64.getEncoder().withoutPadding().encodeToString(digest)

        val sig = signer.externalAuthenticate(challenge)

        val ok = Signature.getInstance("NONEwithECDSA", BC_PROVIDER).run {
            initVerify(cert.publicKey)
            update(digest)
            verify(sig)
        }
        assertTrue(ok, "signature must verify against the identity's certificate public key")
    }
}
