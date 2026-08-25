package de.gematik.zeta.cli.popp

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.long
import de.gematik.zeta.catalog.Environment
import de.gematik.zeta.cli.client.ZetaSessionCommand
import de.gematik.zeta.cli.client.applyCliHttpDefaults
import de.gematik.zeta.cli.client.originOf
import kotlinx.coroutines.runBlocking

/**
 * `zeta popp standard` — drive the PoPP service through the **Standard** scenario
 * (`cardConnectionType=*-standard`), executing each APDU round against a physically inserted eGK
 * that the client reads directly. Today that is a **contact** PC/SC card reader; contactless (PACE)
 * and remote card terminals are future transports under the same `standard` verb.
 *
 * The WebSocket flow is identical to `popp kartos` (`Start → StandardScenario → ScenarioResponse
 * → … → Token`); only the APDU transport differs — here each `commandApdu` is transmitted straight
 * to the card via `javax.smartcardio`.
 */
class PoppStandardCommand : ZetaSessionCommand(name = "standard") {

    private val reader: String? by option(
        "--reader",
        metavar = "NAME",
        envvar = "ZETA_POPP_READER",
        help = "PC/SC card reader name (substring match). Default: the reader with a card " +
            "inserted. (env: ZETA_POPP_READER)",
    )

    private val waitSeconds: Long by option(
        "--wait",
        metavar = "SECONDS",
        help = "Seconds to wait for a card when none is present. Default: 0 (fail immediately).",
    ).long().default(0)

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
        "Retrieve a PoPP token via the Standard flow, reading a physical eGK in a contact card reader."

    override fun runCommand() {
        val serviceUrl = serviceUrlOverride ?: poppServiceUrlFor(env)
        openSession(resource = originOf(serviceUrl), scopes = listOf("popp")) { sdk, _ ->
            val token = runBlocking {
                runCardPoppFlow(sdk, reader, waitSeconds, serviceUrl) { applyCliHttpDefaults(cliConfig) }
            }
            emitPoppToken(token, cliConfig.outputFormat, colorize)
        }
    }
}
