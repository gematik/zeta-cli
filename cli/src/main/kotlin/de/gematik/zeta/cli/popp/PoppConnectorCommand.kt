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

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import de.gematik.zeta.catalog.Environment
import de.gematik.zeta.cli.client.ZetaSessionCommand
import de.gematik.zeta.cli.client.applyCliHttpDefaults
import de.gematik.zeta.cli.client.originOf
import de.gematik.zeta.cli.connector.openConnectorSession
import de.gematik.zeta.cli.connector.resolveSelectedKonFile
import kotlinx.coroutines.runBlocking

/**
 * `zeta popp connector [EGK_HANDLE]` — drive the PoPP service through the Connector
 * scenario and print the resulting token.
 *
 * The Connector is needed twice: once (optionally) for zeta auth, and once for the eGK
 * `StartCardSession` / `SecureSendAPDU` / `StopCardSession` calls that drive the PoPP
 * flow. When zeta auth uses the Connector method, its session is reused here — zero extra
 * round trips. When zeta auth uses PKCS#12, we open a dedicated Connector session for the
 * PoPP flow and close it before returning.
 */
class PoppConnectorCommand : ZetaSessionCommand(name = "connector") {

    /**
     * Card handle of the eGK to use for the popp flow. Optional: when exactly one eGK is
     * visible to the Connector we auto-pick it; with zero or multiple, we error with the
     * available cards listed (use `zeta connector get cards` for the full table).
     */
    private val egkHandleArg: String? by argument(
        name = "EGK_HANDLE",
        help = "eGK card handle. Auto-selected when exactly one eGK is visible to the Connector.",
    ).optional()

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

    private val connectionType: ConnectionType by option(
        "--connection",
        metavar = "TYPE",
        envvar = "ZETA_POPP_CONNECTION",
        help = "Smartcard connection type: contact or contactless. Default: contact. " +
            "(env: ZETA_POPP_CONNECTION)",
    ).enum<ConnectionType>(ignoreCase = true).default(ConnectionType.CONTACT)

    override fun help(context: Context) =
        "Retrieve a PoPP token via the Connector / signed-scenario flow."

    override fun runCommand() {
        val serviceUrl = serviceUrlOverride ?: poppServiceUrlFor(env)
        val config = PoppCardConfig(
            transport = CardTransport.CONNECTOR,
            serviceUrl = serviceUrl,
            connection = connectionType,
            egkHandle = egkHandleArg,
        )
        openSession(resource = originOf(serviceUrl), scopes = listOf("popp")) { sdk, authSession ->
            val poppSession = authSession ?: openConnectorSession(
                konPath = cliConfig.resolveSelectedKonFile(),
                connectTimeout = cliConfig.connectTimeout,
                requestTimeout = cliConfig.requestTimeout,
            )
            try {
                // sdk.ws() handles discover/register/authenticate on first call when needed,
                // so we don't pre-flight an explicit sdk.authenticate() here — that variant
                // skips discover/register and fails on cold profiles.
                val token = runBlocking {
                    runPoppFlow(sdk, config, poppSession) { applyCliHttpDefaults(cliConfig) }
                }
                emitPoppToken(token, cliConfig.outputFormat, colorize)
            } finally {
                // Only close what we opened; the auth session is owned by openSession().
                if (authSession == null) poppSession.close()
            }
        }
    }
}
