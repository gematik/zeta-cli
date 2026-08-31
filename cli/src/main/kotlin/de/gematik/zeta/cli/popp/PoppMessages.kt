package de.gematik.zeta.cli.popp

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Messages exchanged on the popp `/token-generation-ehc` WebSocket. Field names are pinned
 * to the schema at <https://github.com/gematik/api-popp/blob/main/src/openapi/I_PoPP_Token_Generation.yaml>
 * — getting them wrong is a runtime bug the compiler can't catch.
 *
 * Serialised with kotlinx-serialization's polymorphic format using a `type` discriminator,
 * matching what the popp service emits/accepts:
 * ```
 * { "type": "Start", "version": "1.0.0", "cardConnectionType": "contact-connector", … }
 * ```
 *
 * Both scenarios are modelled: the server picks one from the `cardConnectionType` we send in
 * [StartMessage] and drives it to a [TokenMessage].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
internal sealed interface PoppMessage

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("Start")
internal data class StartMessage(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val version: String = "1.0.0",
    val cardConnectionType: String,
    val clientSessionId: String,
) : PoppMessage

@Serializable
@SerialName("ConnectorScenario")
internal data class ConnectorScenarioMessage(
    val version: String,
    val signedScenario: String,
) : PoppMessage

/**
 * Sent by the server when the client used a `*-standard` `cardConnectionType`: the APDU rounds
 * arrive unsigned, for the client to run against the card it holds itself.
 */
@Serializable
@SerialName("StandardScenario")
internal data class StandardScenarioMessage(
    val version: String,
    val clientSessionId: String,
    val sequenceCounter: Int,
    val timeSpan: Long,
    val steps: List<ScenarioStep> = emptyList(),
) : PoppMessage

@Serializable
internal data class ScenarioStep(
    val commandApdu: String,
    val expectedStatusWords: List<String> = emptyList(),
)

@Serializable
@SerialName("ScenarioResponse")
internal data class ScenarioResponseMessage(
    val steps: List<String>,
) : PoppMessage

@Serializable
@SerialName("Token")
internal data class TokenMessage(
    val token: String,
) : PoppMessage

@Serializable
@SerialName("Error")
internal data class ErrorMessage(
    val errorCode: String,
    val errorDetail: String? = null,
) : PoppMessage

/**
 * Thrown by [PoppClient] when the server sends an `ErrorMessage` or the protocol diverges.
 *
 * The popp server intentionally returns an empty [errorDetail] as a security measure, so we
 * collapse `null`/blank details to no suffix at all — otherwise the exception message
 * surfaces as `ErrorEgkHandling:` with a dangling colon and zero diagnostic value.
 */
internal class PoppProtocolException(
    val errorCode: String,
    val errorDetail: String?,
) : RuntimeException(
    buildString {
        append(errorCode)
        val detail = errorDetail?.takeIf { it.isNotBlank() }
        if (detail != null) append(": ").append(detail)
    },
)
