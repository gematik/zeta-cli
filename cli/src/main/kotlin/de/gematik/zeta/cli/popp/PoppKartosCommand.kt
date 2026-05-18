package de.gematik.zeta.cli.popp

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import de.gematik.zeta.cli.client.ZetaSessionCommand
import de.gematik.zeta.cli.client.applyCliHttpDefaults
import de.gematik.zeta.cli.client.originOf
import de.gematik.zeta.cli.trace.Tracer
import de.gematik.zeta.sdk.ZetaSdkClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.UUID

private val log = KotlinLogging.logger {}

private const val DEFAULT_SERVICE_URL =
    "wss://popp.dev.poppservice.de/popp/practitioner/api/v1/token-generation-ehc"

/**
 * `zeta popp kartos --image PATH` — drive the PoPP service through the **Standard** scenario,
 * executing each APDU round on a `kartos` smartcard simulator child process backed by an
 * XML card image. No connector required.
 *
 * The popp WebSocket flow mirrors `popp connector`: `Start → StandardScenario →
 * ScenarioResponse → … → Token`. Each [StandardScenarioMessage] carries plain
 * [ScenarioStep]s (no JWT signing) — we forward the `commandApdu` hex strings to
 * `kartos pipe <image>` one per line on stdin and read response APDU hex strings back
 * from stdout. Kartos stderr is logged at INFO under the `kartos.stderr` logger name.
 */
class PoppKartosCommand : ZetaSessionCommand(name = "kartos") {

    private val image: Path by option(
        "-i", "--image",
        metavar = "PATH",
        envvar = "ZETA_POPP_KARTOS_IMAGE",
        help = "XML card image to load into the kartos smartcard simulator. " +
            "(env: ZETA_POPP_KARTOS_IMAGE)",
    ).path(mustExist = true, canBeFile = true, canBeDir = false).required()

    private val executable: String by option(
        "--kartos-bin",
        metavar = "PATH",
        envvar = "ZETA_KARTOS_BIN",
        help = "Path to the kartos executable. Default: 'kartos' on PATH. (env: ZETA_KARTOS_BIN)",
    ).default("kartos")

    private val serviceUrl: String by option(
        "--service-url",
        metavar = "URL",
        envvar = "ZETA_POPP_SERVICE_URL",
        help = "popp service WebSocket URL. (env: ZETA_POPP_SERVICE_URL)",
    ).default(DEFAULT_SERVICE_URL)

    override fun help(context: Context) =
        "Retrieve a PoPP token via the Standard / kartos-simulator flow."

    override fun runCommand() {
        openSession(resource = originOf(serviceUrl), scopes = listOf("popp")) { sdk, _ ->
            // The Connector session (when present) isn't used in the Standard flow — kartos
            // executes the APDUs locally. ZetaSessionCommand will close it for us.
            //
            // Run SDK authenticate up-front so its work (SDS load + ExternalAuthenticate when
            // tokens are cold) stays scoped to a sibling `sdk.authenticate` span instead of
            // mixing into popp.flow.
            runBlocking {
                Tracer.spanSuspend("sdk.authenticate") { sdk.authenticate().getOrThrow() }
            }
            val token = runPoppFlow(sdk)
            emitPoppToken(token, cliConfig.outputFormat, colorize)
        }
    }

    private fun runPoppFlow(sdk: ZetaSdkClient): String = runBlocking {
        Tracer.spanSuspend("popp.flow", attrs = mapOf("scenario" to "kartos")) {
            // Spawn the simulator once for the whole popp flow — card state needs to persist
            // across APDU rounds; reopening on every StandardScenarioMessage would reset it.
            KartosProcess.spawn(image, executable).use { kartos ->
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
    }
}
