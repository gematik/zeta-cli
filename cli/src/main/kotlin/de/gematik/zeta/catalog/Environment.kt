package de.gematik.zeta.catalog

import java.net.URI

/** A TI platform environment and the service-discovery host that serves its catalog. */
enum class Environment(val host: String) {
    DEV("service-discovery.dev.ti-platform.de"),
    REF("service-discovery.ref.ti-platform.de"),
    TEST("service-discovery.test.ti-platform.de"),
    PROD("service-discovery.prod.ti-platform.de"),
    ;

    val catalogUrl: String get() = "https://$host/catalog.json"
}

/**
 * Derives the environment from a PoPP token's `iss` by the infix in its host — `.dev.`, `.ref.`,
 * `.test.`, `.prod.`. Returns null when none matches (the caller treats that as an error).
 */
fun environmentFromIssuer(iss: String): Environment? {
    val host = runCatching { URI(iss).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: iss
    return when {
        ".dev." in host -> Environment.DEV
        ".ref." in host -> Environment.REF
        ".test." in host -> Environment.TEST
        ".prod." in host -> Environment.PROD
        else -> null
    }
}
