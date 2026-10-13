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
package de.gematik.connector.crypto

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.isismtt.x509.AdmissionSyntax

private val log = KotlinLogging.logger {}

/**
 * X.509 helpers for gematik card certificates.
 *
 * The TI's SMC-B / HBA / SMC-KT cards encode their stable identifier (the **Telematik-ID**)
 * in the ISIS-MTT *admission* extension (`1.3.36.8.3.3`) under
 * `Admissions[0].professionInfos[0].registrationNumber`. The structure (RFC 4334-adjacent,
 * defined in ISIS-MTT v1.1) is:
 *
 * ```
 * AdmissionSyntax ::= SEQUENCE {
 *   admissionAuthority      GeneralName OPTIONAL,
 *   contentsOfAdmissions    SEQUENCE OF Admissions
 * }
 * Admissions ::= SEQUENCE {
 *   admissionAuthority      [0] EXPLICIT GeneralName  OPTIONAL,
 *   namingAuthority         [1] EXPLICIT NamingAuthority OPTIONAL,
 *   professionInfos         SEQUENCE OF ProfessionInfo
 * }
 * ProfessionInfo ::= SEQUENCE {
 *   namingAuthority      [0] EXPLICIT NamingAuthority OPTIONAL,
 *   professionItems          SEQUENCE OF DirectoryString,
 *   professionOIDs           SEQUENCE OF OBJECT IDENTIFIER OPTIONAL,
 *   registrationNumber       PrintableString OPTIONAL,   -- ← Telematik-ID
 *   addProfessionInfo        OCTET STRING OPTIONAL
 * }
 * ```
 *
 * Bouncy Castle's [AdmissionSyntax] decodes this verbatim, so we don't roll our own
 * ASN.1 walker. BC is on the runtime classpath via the Zeta SDK.
 */

private const val ADMISSION_OID = "1.3.36.8.3.3"

/** Parse a DER-encoded X.509 certificate. Throws [IllegalArgumentException] on bad bytes. */
fun ByteArray.parseX509(): X509Certificate {
    val factory = CertificateFactory.getInstance("X.509")
    return try {
        factory.generateCertificate(ByteArrayInputStream(this)) as X509Certificate
    } catch (e: Exception) {
        throw IllegalArgumentException("not a DER X.509 certificate: ${e.message}", e)
    }
}

/**
 * Extract the Telematik-ID from a card cert's admission extension, or `null` if the
 * extension is missing or doesn't carry a `registrationNumber` (e.g. CA certs, non-card
 * certs, or non-TI certificates).
 *
 * The walker takes the first `Admissions[0].professionInfos[0]` — TI cards never carry
 * more than one entry per level. If a future cert format breaks that, this returns
 * `null` rather than misreading.
 */
fun X509Certificate.telematikId(): String? {
    val raw = getExtensionValue(ADMISSION_OID) ?: return null
    return try {
        // The extension value is wrapped in an OCTET STRING; unwrap to get the inner DER.
        val octets = ASN1OctetString.getInstance(raw).octets
        val syntax = AdmissionSyntax.getInstance(ASN1Primitive.fromByteArray(octets))
        syntax.contentsOfAdmissions
            ?.firstOrNull()
            ?.professionInfos
            ?.firstOrNull()
            ?.registrationNumber
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        log.debug(e) { "Could not parse admission extension on certificate ${subjectX500Principal}" }
        null
    }
}
