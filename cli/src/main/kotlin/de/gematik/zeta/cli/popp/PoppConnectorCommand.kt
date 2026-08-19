package de.gematik.zeta.cli.popp

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import de.gematik.zeta.cli.client.ZetaSessionCommand
import de.gematik.zeta.cli.client.applyCliHttpDefaults
import de.gematik.zeta.cli.client.originOf
import de.gematik.zeta.cli.connector.openConnectorSession
import de.gematik.zeta.cli.connector.resolveSelectedKonFile
import kotlinx.coroutines.runBlocking

/**
 * `zeta popp connector [EGK_HANDLE]` — drive the PoPP service through the Connector
 * scenario and print the resulting token.
 *
 * The Connector is needed twice: once (optionally) for zeta auth, and once for the eGK
 * `StartCardSession` / `SecureSendAPDU` / `StopCardSession` calls that drive the PoPP
 * flow. When zeta auth uses the Connector method, its session is reused here — zero extra
 * round trips. When zeta auth uses PKCS#12, we open a dedicated Connector session for the
 * PoPP flow and close it before returning.
 */
class PoppConnectorCommand : ZetaSessionCommand(name = "connector") {

    /**
     * Card handle of the eGK to use for the popp flow. Optional: when exactly one eGK is
     * visible to the Connector we auto-pick it; with zero or multiple, we error with the
     * available cards listed (use `zeta connector get cards` for the full table).
     */
    private val egkHandleArg: String? by argument(
        name = "EGK_HANDLE",
        help = "eGK card handle. Auto-selected when exactly one eGK is visible to the Connector.",
    ).optional()

    private val serviceUrl: String by option(
        "--service-url",
        metavar = "URL",
        envvar = "ZETA_POPP_SERVICE_URL",
        help = "popp service WebSocket URL. (env: ZETA_POPP_SERVICE_URL)",
    ).default(DEFAULT_SERVICE_URL)

    private val connectionType: ConnectionType by option(
        "--connection",
        metavar = "TYPE",
        envvar = "ZETA_POPP_CONNECTION",
        help = "Smartcard connection type: contact or contactless. Default: contact. " +
            "(env: ZETA_POPP_CONNECTION)",
    ).enum<ConnectionType>(ignoreCase = true).default(ConnectionType.CONTACT)

    override fun help(context: Context) =
        "Retrieve a PoPP token via the Connector / signed-scenario flow."

    override fun runCommand() {
        openSession(resource = originOf(serviceUrl), scopes = listOf("popp")) { sdk, authSession ->
            val poppSession = authSession ?: openConnectorSession(
                konPath = cliConfig.resolveSelectedKonFile(),
                connectTimeout = cliConfig.connectTimeout,
                requestTimeout = cliConfig.requestTimeout,
                proxy = cliConfig.proxy,
            )
            try {
                // sdk.ws() handles discover/register/authenticate on first call when needed,
                // so we don't pre-flight an explicit sdk.authenticate() here — that variant
                // skips discover/register and fails on cold profiles.
                val token = runBlocking {
                    runConnectorPoppFlow(sdk, poppSession, egkHandleArg, connectionType, serviceUrl) {
                        applyCliHttpDefaults(cliConfig)
                    }
                }
                emitPoppToken(token, cliConfig.outputFormat, colorize)
            } finally {
                // Only close what we opened; the auth session is owned by openSession().
                if (authSession == null) poppSession.close()
            }
        }
    }

    private companion object {
        const val DEFAULT_SERVICE_URL =
            "wss://popp.dev.poppservice.de/popp/practitioner/api/v1/token-generation-ehc"
    }
}
