package de.gematik.zeta.cli.popp

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json

private val log = KotlinLogging.logger {}

/**
 * Drive popp's `/token-generation-ehc` WebSocket through either the **Connector** or
 * **Standard** scenario: `Start → (Connector|Standard)Scenario → ScenarioResponse → … → Token`.
 *
 * Stateless apart from the session. Each scenario is one method with explicit dispatch on
 * the message `type` discriminator — easier to follow than the Spring `@EventListener`
 * chain in the Java reference client.
 *
 * The client never talks to the card itself — it forwards each APDU round through the
 * caller-supplied callback. That keeps this class testable with a stub callback and
 * decouples it from both the connector module and any local card simulator.
 */
internal class PoppClient(
    private val session: DefaultClientWebSocketSession,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Send [start] and run the request/response loop until the server returns a
     * [TokenMessage] (returned) or [ErrorMessage] (thrown as [PoppProtocolException]).
     *
     * The server may send multiple `ConnectorScenarioMessage`s in sequence (one per APDU
     * round) before issuing the final `TokenMessage`; we handle that by looping.
     */
    suspend fun runConnectorScenario(
        start: StartMessage,
        secureSendApdu: suspend (signedScenario: String) -> List<String>,
    ): String {
        sendMessage(start)
        while (true) {
            when (val msg = receiveMessage()) {
                is ConnectorScenarioMessage -> {
                    val responseSteps = secureSendApdu(msg.signedScenario)
                    sendMessage(ScenarioResponseMessage(steps = responseSteps))
                }

                is TokenMessage -> {
                    log.info { "PoPP token received (${msg.token.length} chars)" }
                    return msg.token
                }

                is ErrorMessage -> throw PoppProtocolException(msg.errorCode, msg.errorDetail)

                is StandardScenarioMessage -> throw PoppProtocolException(
                    "UNEXPECTED_MESSAGE",
                    "Server returned StandardScenarioMessage; this client only supports the " +
                        "Connector flow (cardConnectionType=*-connector).",
                )

                is StartMessage,
                is ScenarioResponseMessage -> throw PoppProtocolException(
                    "UNEXPECTED_MESSAGE",
                    "Server echoed a client-only message type: ${msg::class.simpleName}",
                )
            }
        }
    }

    /**
     * Send [start] and drive the **Standard** scenario: the server delivers plain
     * [StandardScenarioMessage]s (no JWT), [executeApdus] runs the steps against the
     * card, and the response APDU hex strings are returned in a [ScenarioResponseMessage].
     */
    suspend fun runStandardScenario(
        start: StartMessage,
        executeApdus: suspend (List<ScenarioStep>) -> List<String>,
    ): String {
        sendMessage(start)
        while (true) {
            when (val msg = receiveMessage()) {
                is StandardScenarioMessage -> {
                    val responseSteps = executeApdus(msg.steps)
                    sendMessage(ScenarioResponseMessage(steps = responseSteps))
                }

                is TokenMessage -> {
                    log.info { "PoPP token received (${msg.token.length} chars)" }
                    return msg.token
                }

                is ErrorMessage -> throw PoppProtocolException(msg.errorCode, msg.errorDetail)

                is ConnectorScenarioMessage -> throw PoppProtocolException(
                    "UNEXPECTED_MESSAGE",
                    "Server returned ConnectorScenarioMessage; this client only supports the " +
                        "Standard flow (cardConnectionType=*-standard).",
                )

                is StartMessage,
                is ScenarioResponseMessage -> throw PoppProtocolException(
                    "UNEXPECTED_MESSAGE",
                    "Server echoed a client-only message type: ${msg::class.simpleName}",
                )
            }
        }
    }

    private suspend fun sendMessage(msg: PoppMessage) {
        val text = json.encodeToString<PoppMessage>(msg)
        log.debug { "popp send: $text" }
        session.send(Frame.Text(text))
    }

    private suspend fun receiveMessage(): PoppMessage {
        for (frame in session.incoming) {
            when (frame) {
                is Frame.Text -> {
                    val text = frame.readText()
                    log.debug { "popp recv: $text" }
                    return json.decodeFromString<PoppMessage>(text)
                }

                is Frame.Close -> throw PoppProtocolException(
                    "WS_CLOSED",
                    "WebSocket closed by server before a TokenMessage arrived",
                )

                is Frame.Binary -> log.warn { "ignoring ${frame.readBytes().size}-byte binary frame from popp" }

                else -> { /* ping/pong */ }
            }
        }
        throw PoppProtocolException("WS_CLOSED", "WebSocket incoming channel ended")
    }
}
