/*
 * Copyright (Change Date see Readme), gematik GmbH
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the
 * European Commission – subsequent versions of the EUPL (the "Licence").
 * You may not use this work except in compliance with the Licence.
 *
 * You find a copy of the Licence in the "Licence" file or at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.
 * In case of changes by gematik GmbH find details in the "Readme" file.
 *
 * See the Licence for the specific language governing permissions and limitations under the Licence.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 */
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
