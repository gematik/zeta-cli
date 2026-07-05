package de.gematik.zeta.stress.identity

import io.github.oshai.kotlinlogging.KotlinLogging
import org.bouncycastle.asn1.isismtt.ISISMTTObjectIdentifiers
import org.bouncycastle.asn1.isismtt.x509.AdmissionSyntax
import org.bouncycastle.cert.X509CertificateHolder

private val log = KotlinLogging.logger {}

/**
 * The gematik Telematik-ID of an SMC-B, read from the AUT certificate's ISIS-MTT Admission
 * extension (`id-isismtt-at-admission`, OID 1.3.36.8.3.3) → `professionInfo.registrationNumber`.
 * This is the value a PoPP token carries as `actorId`, so it's how we bind a token to a identity.
 *
 * Pure ASN.1 structure parsing — no EC provider needed (the brainpool AUT key is never touched),
 * so this works on the DER cert blob straight from the DB. Returns null if the extension or a
 * registration number is absent.
 */
fun telematikIdOf(certDer: ByteArray): String? =
    try {
        val ext = X509CertificateHolder(certDer)
            .getExtension(ISISMTTObjectIdentifiers.id_isismtt_at_admission) ?: return null
        AdmissionSyntax.getInstance(ext.parsedValue).contentsOfAdmissions
            .asSequence()
            .flatMap { it.professionInfos?.asSequence() ?: emptySequence() }
            .mapNotNull { it.registrationNumber }
            .firstOrNull { it.isNotBlank() }
    } catch (e: Exception) {
        log.debug { "No Telematik-ID in cert: ${e.message}" }
        null
    }
