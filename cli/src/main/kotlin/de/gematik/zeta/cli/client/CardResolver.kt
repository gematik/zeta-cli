package de.gematik.zeta.cli.client

import com.github.ajalt.clikt.core.UsageError
import de.gematik.connector.ConnectorClient
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Resolve a Connector SMC-B card handle from one of three identifiers — exactly one of
 * [cardHandle], [iccsn], or [telematikId] must be non-null. Mutual exclusion is enforced
 * upstream by [ZetaSessionCommand]'s auth-selection validation; this function trusts that.
 *
 * The explicit-handle case issues zero Connector requests. For ICCSN / Telematik-ID we
 * enumerate SMC-Bs via [ConnectorClient.listSmcbCards] **exactly once** (which includes
 * one ReadCardCertificate per card) and reuse the resulting list for both the lookup
 * and the on-miss error listing — the previous version hit the Connector twice on miss.
 *
 * @throws UsageError when no card matches; the message lists every visible SMC-B so the
 *   operator can pick a valid identifier.
 */
internal suspend fun resolveSmcbCardHandle(
    connector: ConnectorClient,
    cardHandle: String?,
    iccsn: String?,
    telematikId: String?,
): String {
    cardHandle?.let { return it }

    val cards = connector.listSmcbCards()
    val match: ConnectorClient.SmcbCard? = when {
        iccsn != null -> cards.firstOrNull { it.iccsn == iccsn }

        telematikId != null -> {
            val matches = cards.filter { it.telematikId == telematikId }
            if (matches.size > 1) {
                log.info {
                    "Multiple SMC-Bs with Telematik-ID '$telematikId' (${matches.size}); " +
                        "picking the newest cert"
                }
            }
            // Newest-cert-wins on a Telematik-ID tie (typical card-renewal window).
            matches.maxByOrNull { it.certificate?.notBefore?.time ?: Long.MIN_VALUE }
        }

        else -> error("unreachable: validateAuthSelection ensured one identifier is set")
    }

    return match?.cardHandle ?: throw UsageError(buildAvailabilityListing(iccsn, telematikId, cards))
}

private fun buildAvailabilityListing(
    iccsn: String?,
    telematikId: String?,
    cards: List<ConnectorClient.SmcbCard>,
): String = buildString {
    val criterion = when {
        iccsn != null -> "ICCSN '$iccsn'"
        telematikId != null -> "Telematik-ID '$telematikId'"
        else -> "the supplied identifier"
    }
    append("no SMC-B matches $criterion")
    if (cards.isEmpty()) {
        append(" (no SMC-Bs visible to the Connector)")
    } else {
        appendLine()
        append("available SMC-Bs:")
        cards.forEach { c ->
            appendLine()
            append(
                "  ${c.ctId} / slot ${c.slotId}   handle=${c.cardHandle}   " +
                    "iccsn=${c.iccsn ?: "<unknown>"}   tid=${c.telematikId ?: "<unknown>"}",
            )
        }
    }
}
