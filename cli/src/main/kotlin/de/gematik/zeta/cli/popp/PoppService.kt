package de.gematik.zeta.cli.popp

import de.gematik.zeta.catalog.Environment

/** The token-generation path served by the PoPP service (same across environments). */
internal const val POPP_TOKEN_PATH = "/popp/practitioner/api/v1/token-generation-ehc"

/**
 * The PoPP-service WebSocket URL for a TI [env], using the same `popp.<env>.poppservice.de` host-infix
 * scheme that [de.gematik.zeta.catalog.environmentFromIssuer] keys on. `poppServiceUrlFor(DEV)` equals
 * the `DEFAULT_SERVICE_URL` hard-coded in the popp commands. The prod host is an assumption — callers
 * expose an override (`--popp-service-url`) for when it differs.
 */
internal fun poppServiceUrlFor(env: Environment): String =
    "wss://popp.${env.name.lowercase()}.poppservice.de$POPP_TOKEN_PATH"
