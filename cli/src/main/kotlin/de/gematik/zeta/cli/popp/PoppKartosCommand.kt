package de.gematik.zeta.cli.popp

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.path
import de.gematik.zeta.catalog.Environment
import de.gematik.zeta.cli.client.ZetaSessionCommand
import de.gematik.zeta.cli.client.applyCliHttpDefaults
import de.gematik.zeta.cli.client.originOf
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

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

    private val env: Environment by option(
        "--env",
        metavar = "ENV",
        envvar = "ZETA_ENV",
        help = "TI environment selecting the popp service: dev (default), ref, test, or prod. " +
            "Overridden by --service-url. (env: ZETA_ENV)",
    ).enum<Environment>(ignoreCase = true).default(Environment.DEV)

    private val serviceUrlOverride: String? by option(
        "--service-url",
        metavar = "URL",
        envvar = "ZETA_POPP_SERVICE_URL",
        help = "popp service WebSocket URL. Overrides --env. (env: ZETA_POPP_SERVICE_URL)",
    )

    override fun help(context: Context) =
        "Retrieve a PoPP token via the Standard / kartos-simulator flow."

    override fun runCommand() {
        val serviceUrl = serviceUrlOverride ?: poppServiceUrlFor(env)
        val config = PoppCardConfig(
            transport = CardTransport.KARTOS,
            serviceUrl = serviceUrl,
            kartosImage = image,
            kartosBin = executable,
        )
        openSession(resource = originOf(serviceUrl), scopes = listOf("popp")) { sdk, _ ->
            // The Connector session (when present) isn't used in the Standard flow — kartos
            // executes the APDUs locally. ZetaSessionCommand will close it for us.
            val token = runBlocking { runPoppFlow(sdk, config) { applyCliHttpDefaults(cliConfig) } }
            emitPoppToken(token, cliConfig.outputFormat, colorize)
        }
    }
}
