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
import de.gematik.zeta.cli.connector.openConnectorSession
import de.gematik.zeta.cli.connector.traced
import de.gematik.zeta.cli.connector.tracedUnder
import de.gematik.zeta.cli.trace.Tracer
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
                connectorConfigName = cliConfig.connectorConfig,
                connectTimeout = cliConfig.connectTimeout,
                requestTimeout = cliConfig.requestTimeout,
                proxy = cliConfig.proxy,
            )
            try {
                // sdk.ws() handles discover/register/authenticate on first call when needed,
                // so we don't pre-flight an explicit sdk.authenticate() here — that variant
                // skips discover/register and fails on cold profiles.
                val token = runPoppFlow(sdk, poppSession)
                emitPoppToken(token, cliConfig.outputFormat, colorize)
            } finally {
                // Only close what we opened; the auth session is owned by openSession().
                if (authSession == null) poppSession.close()
            }
        }
    }

    private fun runPoppFlow(sdk: ZetaSdkClient, session: ConnectorSession): String = runBlocking {
        Tracer.spanSuspend("popp.flow", attrs = mapOf("scenario" to "connector")) {
            // First Connector touch from popp's side. When sdk.authenticate already loaded
            // the SDS (cold token cache), this returns the cached ConnectorClient; otherwise
            // the http.request /connector.sds shows up as a sibling here.
            val connector = session.connector()
            val egkHandle = resolveEgkHandle(connector)
            val cardSessionId = session.traced("startCardSession") { connector.startCardSession(egkHandle) }
            log.info { "Connector card session $cardSessionId opened on eGK $egkHandle" }

            try {
                // Capture the parent for ws.recv/send + secureSendApdu: we want those as
                // siblings of popp.connect under popp.flow, not nested inside the
                // long-lived popp.connect span.
                val wsParent = Tracer.current()
                Tracer.spanSuspend("popp.connect", attrs = mapOf("service_url" to serviceUrl)) {
                    var token: String? = null
                    sdk.ws(
                        targetUrl = serviceUrl,
                        builder = { applyCliHttpDefaults(cliConfig) },
                        customHeaders = null,
                    ) {
                        log.info { "popp WS connected: $serviceUrl" }
                        val client = PoppClient(this, wsSpanParent = wsParent)
                        val start = StartMessage(
                            cardConnectionType = connectionType.popp,
                            clientSessionId = cardSessionId,
                        )
                        token = client.runConnectorScenario(start) { signed ->
                            session.tracedUnder(wsParent, "secureSendApdu") {
                                connector.secureSendApdu(signed)
                            }
                        }
                    }
                    token ?: error("popp WebSocket closed without yielding a TokenMessage")
                }
            } finally {
                // Best-effort cleanup: popp often closes the session itself when the flow
                // completes, after which the Connector reports "Unbekannte Session ID"
                // (Code 4288). Either way we just note it and continue — no stack trace.
                runCatching {
                    session.traced("stopCardSession") { connector.stopCardSession(cardSessionId) }
                }.onFailure { e ->
                    log.debug {
                        "stopCardSession($cardSessionId) failed (continuing): " +
                            (e.message?.substringBefore('\n') ?: e::class.simpleName)
                    }
                }
            }
        }
    }

    private suspend fun resolveEgkHandle(connector: ConnectorClient): String {
        egkHandleArg?.let { return it }

        val cards = Tracer.spanSuspend("connector.getCards") {
            connector.getCardsByType(listOf(CardType.Egk))
        }
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
