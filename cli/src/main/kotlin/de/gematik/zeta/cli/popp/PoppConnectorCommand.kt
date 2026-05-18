package de.gematik.zeta.cli.popp

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import de.gematik.connector.ConnectorClient
import de.gematik.connector.api.gematik.conn.cardservicecommon20.CardType
import de.gematik.zeta.cli.client.ZetaSessionCommand
import de.gematik.zeta.cli.client.applyCliHttpDefaults
import de.gematik.zeta.cli.client.originOf
import de.gematik.zeta.cli.connector.ConnectorSession
import de.gematik.zeta.sdk.ZetaSdkClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking

private val log = KotlinLogging.logger {}

private const val DEFAULT_SERVICE_URL =
    "wss://popp.dev.poppservice.de/popp/practitioner/api/v1/token-generation-ehc"

/**
 * `zeta popp connector [EGK_HANDLE]` — drive the PoPP service through the Connector
 * scenario and print the resulting token.
 *
 * The single Connector opened by [ZetaSessionCommand]'s SMC-B auth flow is reused for the
 * `StartCardSession` / `SecureSendAPDU` / `StopCardSession` calls — we don't open a second
 * connection.
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
        help = "Smartcard connection type. Default: contact.",
    ).enum<ConnectionType>(ignoreCase = true).default(ConnectionType.CONTACT)

    override fun help(context: Context) =
        "Retrieve a PoPP token via the Connector / signed-scenario flow."

    override fun runCommand() {
        openSession(resource = originOf(serviceUrl), scopes = listOf("popp")) { sdk, session ->
            if (session == null) {
                throw UsageError(
                    "popp connector needs Connector authentication (the PKCS#12 fallback " +
                        "doesn't reach the Connector's CardService). Pass " +
                        "--connector-telematik-id, --connector-card-iccsn, or " +
                        "--connector-card-handle.",
                )
            }
            val token = runPoppFlow(sdk, session)
            emitPoppToken(token, cliConfig.outputFormat, colorize)
        }
    }

    private fun runPoppFlow(sdk: ZetaSdkClient, session: ConnectorSession): String = runBlocking {
        // First Connector touch: triggers the lazy SDS load behind [ConnectorSession.connector].
        val connector = session.connector()
        val egkHandle = resolveEgkHandle(connector)
        val cardSessionId = connector.startCardSession(egkHandle)
        log.info { "Connector card session $cardSessionId opened on eGK $egkHandle" }

        try {
            var token: String? = null
            sdk.ws(
                targetUrl = serviceUrl,
                builder = { applyCliHttpDefaults(cliConfig) },
                customHeaders = null,
            ) {
                log.info { "popp WS connected: $serviceUrl" }
                val client = PoppClient(this)
                val start = StartMessage(
                    cardConnectionType = connectionType.popp,
                    clientSessionId = cardSessionId,
                )
                token = client.runConnectorScenario(start, connector::secureSendApdu)
            }
            token ?: error("popp WebSocket closed without yielding a TokenMessage")
        } finally {
            // Best-effort cleanup: popp often closes the session itself when the flow
            // completes, after which the Connector reports "Unbekannte Session ID"
            // (Code 4288). Either way we just note it and continue — no stack trace.
            runCatching { connector.stopCardSession(cardSessionId) }
                .onFailure { e ->
                    log.debug {
                        "stopCardSession($cardSessionId) failed (continuing): " +
                            (e.message?.substringBefore('\n') ?: e::class.simpleName)
                    }
                }
        }
    }

    private suspend fun resolveEgkHandle(connector: ConnectorClient): String {
        egkHandleArg?.let { return it }

        val cards = connector.getCardsByType(listOf(CardType.Egk))
        return when (cards.size) {
            1 -> cards.single().cardHandle.also {
                log.info { "Auto-selected eGK card handle: $it" }
            }
            0 -> throw UsageError(
                "No eGK visible to the Connector. Insert one or pass EGK_HANDLE explicitly.",
            )
            else -> throw UsageError(
                buildString {
                    appendLine("Multiple eGKs visible — pass one of these handles explicitly:")
                    cards.forEach { c ->
                        appendLine(
                            "  ${c.cardHandle}   kvnr=${c.kvnr ?: "<unknown>"}   (${c.ctId}/${c.slotId})",
                        )
                    }
                }.trimEnd(),
            )
        }
    }

    enum class ConnectionType(val popp: String) {
        CONTACT("contact-connector"),
        CONTACTLESS("contactless-connector"),
    }
}
