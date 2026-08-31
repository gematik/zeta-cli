package de.gematik.zeta.cli.popp

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import de.gematik.zeta.catalog.Environment
import de.gematik.zeta.cli.client.ZetaSessionCommand
import de.gematik.zeta.cli.client.applyCliHttpDefaults
import de.gematik.zeta.cli.client.originOf
import kotlinx.coroutines.runBlocking

/**
 * `zeta popp standard` — drive the PoPP service through the **Standard** scenario
 * (`cardConnectionType=*-standard`), executing each APDU round against a physical eGK that the
 * client reads directly over PC/SC: inserted in a contact reader, or held against a contactless
 * one, where `--can` opens a PACE channel first.
 *
 * The WebSocket flow is identical to `popp kartos` (`Start → StandardScenario → ScenarioResponse
 * → … → Token`); only the APDU transport differs — here each `commandApdu` is transmitted straight
 * to the card via `javax.smartcardio`.
 */
class PoppStandardCommand : ZetaSessionCommand(name = "standard") {

    private val card by CardReaderOptions()

    private val can: String? by option(
        "--can",
        metavar = "CAN",
        envvar = "ZETA_POPP_CAN",
        help = "Card access number of the contactless eGK — the digits printed on the card. " +
            "Required for --connection contactless. (env: ZETA_POPP_CAN)",
    )

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
        "Retrieve a PoPP token via the Standard flow, reading a physical eGK in a local card reader."

    override fun runCommand() {
        val serviceUrl = serviceUrlOverride ?: poppServiceUrlFor(env)
        val config = PoppCardConfig(
            transport = CardTransport.STANDARD,
            serviceUrl = serviceUrl,
            connection = card.connection,
            reader = card.reader,
            waitSeconds = card.waitSeconds,
            can = can,
            readerOption = card.readerOptionName,
        )
        openSession(resource = originOf(serviceUrl), scopes = listOf("popp")) { sdk, _ ->
            val token = runBlocking { runPoppFlow(sdk, config) { applyCliHttpDefaults(cliConfig) } }
            emitPoppToken(token, cliConfig.outputFormat, colorize)
        }
    }
}
