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
import de.gematik.zeta.cli.trace.Tracer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking

private val log = KotlinLogging.logger {}

/**
 * `zeta authenticate URL` — run the OAuth token-exchange flow with the resource's
 * authorization server and persist the resulting access + refresh tokens. Requires the
 * client to be registered already (`zeta register URL` or `zeta login URL`).
 *
 * Scopes are mandatory — they're what the access token is granted for. The auth server
 * rejects empty-scope token requests, so we make that contract visible at the CLI level.
 */
class AuthenticateCommand : ZetaSessionCommand(name = "authenticate") {
    private val url: String by argument(
        name = "URL",
        help = "URL of the Zeta-protected resource to authenticate against.",
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
        "Exchange the SMC-B subject token for an access + refresh token pair from the authorization server."

    override fun runCommand() {
        val resource = originOf(url)
        log.info { "Authenticating against $resource with scopes $scopes" }
        openSession(resource = resource, scopes = scopes) { sdk, _ ->
            runBlocking {
                Tracer.spanSuspend("sdk.authenticate") { sdk.authenticate().getOrThrow() }
            }
            val entry = loadEntry(resource)
            renderResult(
                CommandResult(
                    operation = "authenticate",
                    ok = entry.status.hasUsableCredentials,
                    endpoint = resource,
                    scopes = scopes,
                    authServer = entry.issuer,
                    status = entry.status,
                    detail = if (entry.status.hasUsableCredentials) null else "no valid tokens issued",
                ),
                entry = entry,
                reveal = reveal,
            )
        }
    }
}
