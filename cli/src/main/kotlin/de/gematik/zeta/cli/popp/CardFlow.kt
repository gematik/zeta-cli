package de.gematik.zeta.cli.popp

import de.gematik.zeta.cli.trace.Tracer
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID

private val log = KotlinLogging.logger {}

/**
 * Drive the PoPP **Standard** scenario once against a physically inserted eGK in a contact PC/SC
 * reader and return the minted token JWT. Mirrors [runKartosPoppFlow] but forwards each APDU round
 * to the real card ([SmartCard]) instead of a `kartos` simulator. [readerName]/[waitSeconds] pick
 * the reader; [wsBuilder] configures the WS HTTP client (timeouts / TLS).
 */
suspend fun runCardPoppFlow(
    sdk: ZetaSdkClient,
    readerName: String?,
    waitSeconds: Long,
    serviceUrl: String,
    wsBuilder: ZetaHttpClientBuilder.() -> Unit,
): String =
    Tracer.spanSuspend("popp.flow", attrs = mapOf("scenario" to "card")) {
        // Hold the card connection open for the whole flow — card state persists across APDU rounds.
        SmartCard.connect(readerName, waitSeconds).use { card ->
            val wsParent = Tracer.current()
            Tracer.spanSuspend("popp.connect", attrs = mapOf("service_url" to serviceUrl)) {
                var token: String? = null
                sdk.ws(targetUrl = serviceUrl, builder = wsBuilder, customHeaders = null) {
                    log.info { "popp WS connected: $serviceUrl" }
                    val client = PoppClient(this, wsSpanParent = wsParent)
                    val start = StartMessage(
                        cardConnectionType = "contact-standard",
                        clientSessionId = UUID.randomUUID().toString(),
                    )
                    token = client.runStandardScenario(start) { steps ->
                        log.debug { "card round: ${steps.size} APDU(s)" }
                        steps.map { card.exchange(it) }
                    }
                }
                token ?: error("popp WebSocket closed without yielding a TokenMessage")
            }
        }
    }
