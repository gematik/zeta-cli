package de.gematik.zeta.cli.lifecycle

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import de.gematik.zeta.cli.ZetaProfileCommand
import de.gematik.zeta.cli.client.originOf
import de.gematik.zeta.cli.sdk.NoopSubjectTokenProvider
import de.gematik.zeta.cli.sdk.buildZetaSdkClient
import de.gematik.zeta.cli.storage.zetaProfilePath
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking

private val log = KotlinLogging.logger {}

/**
 * `zeta logout URL` — mirror of the SDK's `logout()`: revoke the current access + refresh
 * tokens against the authorization server's revocation endpoint and clear them locally.
 * The dynamic-client registration stays intact, so a subsequent `zeta authenticate URL`
 * can mint fresh tokens without re-registering.
 *
 * Logout only revokes + clears tokens, so it never signs a subject token — the client is
 * built with [NoopSubjectTokenProvider] and no `--auth-method` is required, the same
 * auth-free pattern as `discover` / `forget URL`. The stub fails loudly if the SDK ever
 * calls `createSubjectToken` from this path.
 *
 * To wipe everything (registration + tokens + cached well-known), run `zeta forget URL`.
 */
class LogoutCommand : ZetaProfileCommand(name = "logout") {
    private val url: String by argument(
        name = "URL",
        help = "URL of the Zeta-protected resource to log out of.",
    )

    private val reveal: Boolean by option(
        "--reveal",
        envvar = "ZETA_REVEAL",
        help = "Include normally-redacted secrets in the resulting status output. (env: ZETA_REVEAL)",
    ).flag(default = false)

    override fun help(context: Context) =
        "Revoke the access + refresh tokens for this resource. Keeps the client registration."

    override fun runCommand() {
        val resource = originOf(url)
        log.info { "Logging out of $resource" }
        val sdk = buildZetaSdkClient(
            resource = resource,
            scopes = emptyList(),
            storagePath = zetaProfilePath(profile),
            tokenProvider = NoopSubjectTokenProvider,
            cliConfig = cliConfig,
        )
        runBlocking { sdk.logout().getOrThrow() }
        renderEntry(loadEntry(resource), reveal)
    }
}
