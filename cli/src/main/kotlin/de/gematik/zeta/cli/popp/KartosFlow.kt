package de.gematik.zeta.cli.popp

import de.gematik.zeta.cli.trace.Tracer
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.util.UUID

private val log = KotlinLogging.logger {}

/**
 * Drive the PoPP **Standard** scenario once and return the minted token JWT: spawn a `kartos`
 * smartcard simulator over the eGK [image], open the popp WebSocket at [serviceUrl] through [sdk]
 * (which lazily discovers/registers/authenticates on first call), and forward each APDU round to
 * the simulator. [wsBuilder] configures the WS HTTP client (timeouts / TLS).
 *
 * Shared by `zeta popp kartos` (single card) and `zeta stress popp get` (batch over a cohort).
 */
suspend fun runKartosPoppFlow(
    sdk: ZetaSdkClient,
    image: Path,
    kartosBin: String,
    serviceUrl: String,
    wsBuilder: ZetaHttpClientBuilder.() -> Unit,
): String =
    Tracer.spanSuspend("popp.flow", attrs = mapOf("scenario" to "kartos")) {
        // Spawn the simulator once for the whole flow — card state must persist across APDU rounds.
        KartosProcess.spawn(image, kartosBin).use { kartos ->
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
                        log.debug { "kartos round: ${steps.size} APDU(s)" }
                        steps.map { kartos.exchange(it.commandApdu) }
                    }
                }
                token ?: error("popp WebSocket closed without yielding a TokenMessage")
            }
        }
    }
