package de.gematik.connector

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostnameVerificationTest {
    @Test
    fun `SAN-less cert falls back to the subject CN`() {
        val cert = cert(cn = "server")
        assertTrue(verifyHostname(cert, "server"))
        assertTrue(verifyHostname(cert, "SERVER"), "CN match is case-insensitive")
        assertFalse(verifyHostname(cert, "192.168.178.6"))
        assertFalse(verifyHostname(cert, "other"))
    }

    @Test
    fun `present SANs win and the CN is ignored`() {
        val cert = cert(cn = "server", dnsSans = listOf("konnektor.example.com"))
        assertTrue(verifyHostname(cert, "konnektor.example.com"))
        assertTrue(verifyHostname(cert, "KONNEKTOR.EXAMPLE.COM"))
        assertFalse(verifyHostname(cert, "server"), "CN must be ignored once any SAN is present")
    }

    private fun cert(cn: String, dnsSans: List<String> = emptyList()): X509Certificate {
        val keyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val name = X500Name("CN=$cn")
        val now = Date()
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.ONE,
            now,
            Date(now.time + 24 * 60 * 60 * 1000),
            name,
            keyPair.public,
        )
        if (dnsSans.isNotEmpty()) {
            val names = GeneralNames(dnsSans.map { GeneralName(GeneralName.dNSName, it) }.toTypedArray())
            builder.addExtension(Extension.subjectAlternativeName, false, names)
        }
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }
}
