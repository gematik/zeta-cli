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
package de.gematik.zeta.cli.lifecycle

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import de.gematik.zeta.cli.client.ZetaSessionCommand
import de.gematik.zeta.cli.client.originOf
import de.gematik.zeta.cli.state.CommandResult
import de.gematik.zeta.cli.state.isRegistered
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking

private val log = KotlinLogging.logger {}

/**
 * `zeta register URL` — run dynamic client registration (RFC 7591) against the resource's
 * authorization server. Requires the resource's `.well-known` documents to be cached (run
 * `zeta inspect URL` first if not); the SDK chain triggers a fetch otherwise. On success
 * the registration is persisted under the active profile and `zeta status URL` shows
 * `REGISTERED_NO_VALID_TOKENS`.
 *
 * Auth options are required: registration includes proof of the SMC-B role and is signed
 * with the client's `private_key_jwt`, so the subject-token provider must be real.
 */
class RegisterCommand : ZetaSessionCommand(name = "register") {
    private val url: String by argument(
        name = "URL",
        help = "URL of the Zeta-protected resource to register against.",
    )

    private val reveal: Boolean by option(
        "--reveal",
        envvar = "ZETA_REVEAL",
        help = "Include normally-redacted secrets in the resulting status output: the " +
            "`client_secret` and the `registration_access_token`. (env: ZETA_REVEAL)",
    ).flag(default = false)

    override fun help(context: Context) =
        "Run dynamic client registration with the authorization server for this resource."

    override fun runCommand() {
        val resource = originOf(url)
        log.info { "Registering with authorization server for $resource" }
        openSession(resource = resource, scopes = emptyList()) { sdk, _ ->
            runBlocking { sdk.register().getOrThrow() }
            val entry = loadEntry(resource)
            renderResult(
                CommandResult(
                    operation = "register",
                    ok = entry.status.isRegistered,
                    endpoint = resource,
                    scopes = emptyList(),
                    authServer = entry.issuer,
                    status = entry.status,
                    detail = if (entry.status.isRegistered) "client registered" else "registration failed",
                ),
                entry = entry,
                reveal = reveal,
            )
        }
    }
}
