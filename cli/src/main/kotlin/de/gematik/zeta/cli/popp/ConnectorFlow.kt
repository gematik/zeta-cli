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

import com.github.ajalt.clikt.core.UsageError
import de.gematik.connector.ConnectorClient
import de.gematik.connector.api.gematik.conn.cardservicecommon20.CardType
import de.gematik.zeta.cli.connector.ConnectorSession
import de.gematik.zeta.cli.connector.traced
import de.gematik.zeta.cli.connector.tracedUnder
import de.gematik.zeta.cli.trace.Tracer
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Drive the PoPP **Connector** scenario once against an eGK visible to the Konnektor [session] and
 * return the minted token JWT.
 *
 * The Konnektor is touched for `StartCardSession` / `SecureSendAPDU` (per APDU round) /
 * `StopCardSession`; the APDU rounds are forwarded from [PoppClient] straight to
 * `connector.secureSendApdu`.
 */
internal suspend fun runConnectorPoppFlow(
    sdk: ZetaSdkClient,
    session: ConnectorSession,
    config: PoppCardConfig,
    wsBuilder: ZetaHttpClientBuilder.() -> Unit,
): String {
    val connector = session.connector()
    val handle = resolveEgkHandle(connector, config.egkHandle)
    val cardSessionId = session.traced("startCardSession") { connector.startCardSession(handle) }
    log.info { "Connector card session $cardSessionId opened on eGK $handle" }

    return try {
        poppConnect(sdk, config, wsBuilder) { client, wsParent ->
            client.runConnectorScenario(startMessage(config, cardSessionId)) { signed ->
                session.tracedUnder(wsParent, "secureSendApdu") { connector.secureSendApdu(signed) }
            }
        }
    } finally {
        // Best-effort cleanup: popp often closes the session itself when the flow completes, after
        // which the Connector reports "Unbekannte Session ID" (Code 4288). Just note it and continue.
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

private suspend fun resolveEgkHandle(connector: ConnectorClient, egkHandle: String?): String {
    egkHandle?.let { return it }

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
