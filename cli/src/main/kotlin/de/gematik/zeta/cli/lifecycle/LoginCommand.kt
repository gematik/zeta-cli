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
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import de.gematik.zeta.cli.client.ZetaSessionCommand
import de.gematik.zeta.cli.client.originOf
import de.gematik.zeta.cli.state.CommandResult
import de.gematik.zeta.cli.state.hasUsableCredentials
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking

private val log = KotlinLogging.logger {}

/**
 * `zeta login URL` — idempotent shortcut for "get me a usable access token for this
 * resource, doing whichever steps aren't already cached". Equivalent to
 * `zeta register URL && zeta authenticate URL` but skips the registration call when the
 * client is already registered and skips the token exchange when the cached access token
 * is still valid.
 *
 * Prints a small trace before the resulting status so the user knows what actually ran —
 * the whole point of having `login` as a separate verb from `authenticate`.
 */
class LoginCommand : ZetaSessionCommand(name = "login") {
    private val url: String by argument(
        name = "URL",
        help = "URL of the Zeta-protected resource to log into.",
    )

    private val scopes: List<String> by option(
        "-s", "--scope",
        metavar = "NAME",
        envvar = "ZETA_SCOPE",
        help = "OAuth2 scope to request. Repeatable; at least one is required. " +
            "The env var supplies a single scope. (env: ZETA_SCOPE)",
    ).multiple(required = true)

    private val reveal: Boolean by option(
        "--reveal",
        envvar = "ZETA_REVEAL",
        help = "Include normally-redacted secrets in the resulting status output. (env: ZETA_REVEAL)",
    ).flag(default = false)

    override fun help(context: Context) =
        "Register (if needed) and authenticate against the resource. Idempotent — skips steps already cached."

    override fun runCommand() {
        val resource = originOf(url)
        log.info { "Logging in to $resource with scopes $scopes" }
        openSession(resource = resource, scopes = scopes) { sdk, _ ->
            val (ranRegister, ranAuthenticate) = runBlocking { ensureLoggedIn(sdk) }
            renderLogin(resource, ranRegister, ranAuthenticate)
        }
    }

    private fun renderLogin(resource: String, ranRegister: Boolean, ranAuthenticate: Boolean) {
        val entry = loadEntry(resource)
        val steps = buildList {
            if (ranRegister) add("registered")
            if (ranAuthenticate) add("authenticated")
        }
        renderResult(
            CommandResult(
                operation = "login",
                ok = entry.status.hasUsableCredentials,
                endpoint = resource,
                scopes = scopes,
                authServer = entry.issuer,
                status = entry.status,
                detail = if (steps.isEmpty()) "already authenticated — nothing to do"
                else "ran ${steps.joinToString(" + ")}",
            ),
            entry = entry,
            reveal = reveal,
        )
    }
}
