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
import de.gematik.zeta.cli.output.OutputFormat
import de.gematik.zeta.cli.output.renderJson
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

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
            emitToken(token)
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
                val client = PoppClient(this, connector::secureSendApdu)
                val start = StartMessage(
                    cardConnectionType = connectionType.popp,
                    clientSessionId = cardSessionId,
                )
                token = client.runConnectorScenario(start)
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

    /**
     * `text` and `raw` both emit the JWT verbatim — the typical consumer pipes it into
     * `--popp-token` / `PoPP:` header where the compact serialisation is required. Use
     * `-o json` to see the decoded JOSE header and payload.
     */
    private fun emitToken(token: String) {
        when (cliConfig.outputFormat) {
            OutputFormat.RAW, OutputFormat.TEXT -> echo(token)
            OutputFormat.JSON -> echo(renderJson(buildTokenJson(token), colorize = colorize))
        }
    }

    /** Decoded view: JOSE header + payload. Segments that fail to decode are omitted. */
    private fun buildTokenJson(token: String): JsonObject = buildJsonObject {
        decodeJwtSegment(token, segment = 0)?.let { put("header", it) }
        decodeJwtSegment(token, segment = 1)?.let { put("payload", it) }
    }

    enum class ConnectionType(val popp: String) {
        CONTACT("contact-connector"),
        CONTACTLESS("contactless-connector"),
    }
}

/**
 * Decode a single segment (`0` = JOSE header, `1` = payload) of a JWT compact serialisation
 * into a [JsonElement]. Returns `null` for malformed input — the popp service is the
 * authority on token validity, not us; we just want to render what we can.
 */
private fun decodeJwtSegment(token: String, segment: Int): JsonElement? = runCatching {
    val parts = token.split('.')
    require(segment in parts.indices) { "JWT segment $segment out of range (got ${parts.size} parts)" }
    val padded = parts[segment] + "=".repeat((4 - parts[segment].length % 4) % 4)
    val bytes = Base64.getUrlDecoder().decode(padded)
    Json.parseToJsonElement(bytes.decodeToString())
}.getOrNull()
