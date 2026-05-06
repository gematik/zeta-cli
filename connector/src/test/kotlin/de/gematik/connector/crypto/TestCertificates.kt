package de.gematik.connector.crypto

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.isismtt.x509.AdmissionSyntax
import org.bouncycastle.asn1.isismtt.x509.Admissions
import org.bouncycastle.asn1.isismtt.x509.ProfessionInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.Extensions
import org.bouncycastle.asn1.x509.ExtensionsGenerator
import org.bouncycastle.asn1.x509.qualified.MonetaryValue
import org.bouncycastle.asn1.x509.sigi.NameOrPseudonym
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Synthetic SMC-B-style test certificates for the connector unit tests.
 *
 * Builds a self-signed P-256 EC certificate with an ISIS-MTT admission extension
 * (`1.3.36.8.3.3`) carrying a single `ProfessionInfo` with the requested
 * `registrationNumber` (a.k.a. Telematik-ID). This is the exact shape real Connector
 * cards present, so the same parsing path exercised by the tests is what runs in prod.
 */
private val ADMISSION_OID = ASN1ObjectIdentifier("1.3.36.8.3.3")

internal fun testCertWithTelematikId(
    telematikId: String,
    notBefore: Date = Date(),
): X509Certificate =
    buildCert(
        extensions = extensions {
            addExtension(ADMISSION_OID, false, admissionSyntax(telematikId))
        },
        notBefore = notBefore,
    )

internal fun testCertWithoutAdmission(notBefore: Date = Date()): X509Certificate =
    buildCert(extensions = null, notBefore = notBefore)

private fun admissionSyntax(registrationNumber: String): AdmissionSyntax {
    // ProfessionInfo: minimal — only registrationNumber matters here. professionItems is
    // required by the schema (SEQUENCE OF DirectoryString, MIN 1) but its content doesn't
    // affect Telematik-ID extraction.
    val professionInfo = ProfessionInfo(
        /* namingAuthority   */ null,
        /* professionItems   */ arrayOf(org.bouncycastle.asn1.x500.DirectoryString("test")),
        /* professionOIDs    */ null,
        /* registrationNumber*/ registrationNumber,
        /* addProfessionInfo */ null,
    )
    val admissions = Admissions(
        /* admissionAuthority*/ null,
        /* namingAuthority   */ null,
        /* professionInfos   */ arrayOf(professionInfo),
    )
    val contents = DERSequence(ASN1EncodableVector().apply { add(admissions) })
    return AdmissionSyntax(null, contents)
}

private fun extensions(block: ExtensionsGenerator.() -> Unit): Extensions =
    ExtensionsGenerator().apply(block).generate()

private fun buildCert(
    extensions: Extensions?,
    notBefore: Date = Date(),
): X509Certificate {
    val keyPair = generateEcKey()
    val subject = X500Name("CN=test-smcb")
    val notAfter = Date(notBefore.time + 24 * 60 * 60 * 1000)

    val builder = JcaX509v3CertificateBuilder(
        /* issuer    */ subject,
        /* serial    */ BigInteger.ONE,
        /* notBefore */ notBefore,
        /* notAfter  */ notAfter,
        /* subject   */ subject,
        /* publicKey */ keyPair.public,
    )
    extensions?.extensionOIDs?.forEach { oid ->
        val ext: Extension = extensions.getExtension(oid as ASN1ObjectIdentifier)
        builder.addExtension(ext)
    }

    val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
    return JcaX509CertificateConverter().getCertificate(builder.build(signer))
}

private fun generateEcKey(): KeyPair {
    return KeyPairGenerator.getInstance("EC").apply {
        initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
}

// Suppress unused-import warnings for bouncy types referenced only via reflection-shaped APIs.
@Suppress("unused")
private val keepImports = listOf(MonetaryValue::class, NameOrPseudonym::class)
