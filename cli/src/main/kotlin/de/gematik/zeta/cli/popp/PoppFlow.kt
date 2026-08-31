package de.gematik.zeta.cli.popp

import de.gematik.zeta.cli.connector.ConnectorSession
import de.gematik.zeta.cli.trace.Span
import de.gematik.zeta.cli.trace.Tracer
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.util.UUID

private val log = KotlinLogging.logger {}

/** How the card is wired to the reader — the first half of popp's `cardConnectionType`. */
enum class ConnectionType(val wire: String) {
    CONTACT("contact"),
    CONTACTLESS("contactless"),
}

/** Where the eGK lives, and hence which popp scenario drives it. */
enum class CardTransport(val scenario: String) {
    /** An eGK in a card terminal at the Konnektor, driven through signed scenarios. */
    CONNECTOR("connector"),

    /** An eGK in a local PC/SC reader the client talks to directly. */
    STANDARD("standard"),

    /** A `kartos` simulator process standing in for a card. */
    KARTOS("standard"),
}

/**
 * Everything a PoPP flow needs beyond the SDK client: which card to drive, how to reach it, and
 * which popp service to mint at. Shared by the `zeta popp` subcommands and `zeta serve`, so both
 * describe a card the same way.
 *
 * [egkHandle] applies to [CardTransport.CONNECTOR] (null auto-selects the single visible eGK);
 * [reader], [waitSeconds] and [can] to [CardTransport.STANDARD]; [kartosImage] and [kartosBin] to
 * [CardTransport.KARTOS]. [readerOption] is the flag a message should name to pin a reader slot —
 * the same option under a different spelling in `zeta popp standard` and `zeta serve`.
 */
data class PoppCardConfig(
    val transport: CardTransport,
    val serviceUrl: String,
    val connection: ConnectionType = ConnectionType.CONTACT,
    val egkHandle: String? = null,
    val reader: String? = null,
    val waitSeconds: Long = 0,
    val can: String? = null,
    val readerOption: String = "--reader",
    val kartosImage: Path? = null,
    val kartosBin: String = "kartos",
) {
    /** popp's `cardConnectionType`, e.g. `contactless-standard`. */
    val cardConnectionType: String get() = "${connection.wire}-${transport.scenario}"
}

/**
 * Drive one PoPP token flow to completion and return the minted JWT. [connectorSession] is required
 * for [CardTransport.CONNECTOR] and ignored otherwise; [wsBuilder] configures the WebSocket HTTP
 * client (timeouts / TLS).
 */
internal suspend fun runPoppFlow(
    sdk: ZetaSdkClient,
    config: PoppCardConfig,
    connectorSession: ConnectorSession? = null,
    wsBuilder: ZetaHttpClientBuilder.() -> Unit,
): String = Tracer.spanSuspend("popp.flow", attrs = mapOf("scenario" to config.transport.name.lowercase())) {
    when (config.transport) {
        CardTransport.CONNECTOR -> runConnectorPoppFlow(
            sdk = sdk,
            session = requireNotNull(connectorSession) { "the connector card transport needs a Konnektor session" },
            config = config,
            wsBuilder = wsBuilder,
        )

        CardTransport.STANDARD ->
            // Hold the card connection open for the whole flow — card state, and any PACE channel,
            // persist across APDU rounds.
            SmartCard.connect(
                readerName = config.reader,
                waitSeconds = config.waitSeconds,
                connection = config.connection,
                can = config.can,
                readerOption = config.readerOption,
            ).use { card ->
                runStandardPoppFlow(sdk, config, wsBuilder) { steps -> steps.map { card.exchange(it) } }
            }

        CardTransport.KARTOS -> {
            val image = requireNotNull(config.kartosImage) { "the kartos card transport needs a card image" }
            KartosProcess.spawn(image, config.kartosBin).use { kartos ->
                runStandardPoppFlow(sdk, config, wsBuilder) { steps -> steps.map { kartos.exchange(it.commandApdu) } }
            }
        }
    }
}

/** `zeta stress popp get` mints in bulk against a cohort of card images rather than one card. */
internal suspend fun runKartosPoppFlow(
    sdk: ZetaSdkClient,
    image: Path,
    kartosBin: String,
    serviceUrl: String,
    wsBuilder: ZetaHttpClientBuilder.() -> Unit,
): String = runPoppFlow(
    sdk = sdk,
    config = PoppCardConfig(
        transport = CardTransport.KARTOS,
        serviceUrl = serviceUrl,
        kartosImage = image,
        kartosBin = kartosBin,
    ),
    wsBuilder = wsBuilder,
)

/**
 * The **Standard** scenario: the server sends plain [ScenarioStep]s (no JWT), [executeApdus] runs
 * them against whatever holds the card, and the response APDU hex strings go back.
 */
private suspend fun runStandardPoppFlow(
    sdk: ZetaSdkClient,
    config: PoppCardConfig,
    wsBuilder: ZetaHttpClientBuilder.() -> Unit,
    executeApdus: suspend (List<ScenarioStep>) -> List<String>,
): String = poppConnect(sdk, config, wsBuilder) { client, _ ->
    client.runStandardScenario(startMessage(config, UUID.randomUUID().toString())) { steps ->
        log.debug { "card round: ${steps.size} APDU(s)" }
        executeApdus(steps)
    }
}

/**
 * Open the popp WebSocket at the configured service and run [drive] over it. `sdk.ws()` lazily
 * discovers/registers/authenticates on first call, so no explicit pre-flight is needed here.
 *
 * [drive] receives the span that parents the per-message WS spans: they are deliberately siblings
 * of `popp.connect`, since nesting them under a long-lived connection makes the tree unreadable.
 */
internal suspend fun poppConnect(
    sdk: ZetaSdkClient,
    config: PoppCardConfig,
    wsBuilder: ZetaHttpClientBuilder.() -> Unit,
    drive: suspend (PoppClient, Span?) -> String,
): String {
    val wsParent = Tracer.current()
    return Tracer.spanSuspend("popp.connect", attrs = mapOf("service_url" to config.serviceUrl)) {
        var token: String? = null
        sdk.ws(targetUrl = config.serviceUrl, builder = wsBuilder, customHeaders = null) {
            log.info { "popp WS connected: ${config.serviceUrl}" }
            token = drive(PoppClient(this, wsSpanParent = wsParent), wsParent)
        }
        token ?: error("popp WebSocket closed without yielding a TokenMessage")
    }
}

internal fun startMessage(config: PoppCardConfig, clientSessionId: String) = StartMessage(
    cardConnectionType = config.cardConnectionType,
    clientSessionId = clientSessionId,
)
