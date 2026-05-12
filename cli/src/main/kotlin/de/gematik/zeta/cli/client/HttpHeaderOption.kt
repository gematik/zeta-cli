package de.gematik.zeta.cli.client

import com.github.ajalt.clikt.core.UsageError

/**
 * Parse a single curl-style `-H "Name: Value"` argument into a name/value pair. Trims both
 * sides; throws [UsageError] on malformed input so Clikt surfaces a one-line usage message.
 */
internal fun parseHeaderOption(value: String): Pair<String, String> {
    val idx = value.indexOf(':')
    if (idx <= 0) throw UsageError("invalid -H value: '$value' (expected 'Name: Value')")
    return value.substring(0, idx).trim() to value.substring(idx + 1).trim()
}

/**
 * HTTP header that carries a gematik Proof of Patient Presence (PoPP) token to a
 * Zeta-protected resource. Defined by the gematik ZETA spec (A_25669) —
 * https://gemspec.gematik.de/docs/gemSpec/gemSpec_ZETA/latest/#A_25669
 */
internal const val POPP_HEADER_NAME = "PoPP"
