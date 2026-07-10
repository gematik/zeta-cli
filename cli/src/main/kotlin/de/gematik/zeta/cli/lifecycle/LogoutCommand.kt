package de.gematik.zeta.cli.lifecycle

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import de.gematik.zeta.cli.ZetaProfileCommand
import de.gematik.zeta.cli.client.originOf
import de.gematik.zeta.cli.state.CommandResult
import de.gematik.zeta.cli.state.ProfileStores
import de.gematik.zeta.cli.state.entryFor
import de.gematik.zeta.cli.state.hasUsableCredentials
import de.gematik.zeta.cli.state.isRegistered
import de.gematik.zeta.cli.sdk.NoopSubjectTokenProvider
import de.gematik.zeta.cli.sdk.buildZetaSdkClient
import de.gematik.zeta.cli.storage.ProfileDb
import de.gematik.zeta.cli.storage.zetaProfilePath
import de.gematik.zeta.sdk.ZetaSdkClientExtension
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

        // Tokens live under the resource's scope contexts, not a bare `resource` key — so build the
        // SDK with each cached scope-set and revoke there. Building with empty scopes would target an
        // unrelated (empty) context: nothing revoked, and it would then shadow the real one as the
        // "latest" so status wrongly reads NOT_REGISTERED. logout() keeps the registration.
        val db = ProfileDb(zetaProfilePath(profile))
        val contexts = db.contextsForFqdn(resource)
        if (contexts.isEmpty()) {
            echo("No cached session for $resource in profile '$profile'. Nothing to log out.")
            return
        }
        contexts.forEach { ctx ->
            val sdk = buildZetaSdkClient(
                resource = resource,
                scopes = ctx.scopes,
                storagePath = zetaProfilePath(profile),
                tokenProvider = NoopSubjectTokenProvider,
                cliConfig = cliConfig,
            )
            try {
                runBlocking { sdk.logout().getOrThrow() }
            } finally {
                ZetaSdkClientExtension.close(sdk)
            }
        }

        // Report the registered context (logout keeps it) rather than the latest-touched one, which
        // after the loop could be a scope-less discover context that never held a registration.
        val entries = runBlocking { contexts.map { entryFor(ProfileStores(it, db)) } }
        val entry = entries.firstOrNull { it.status.isRegistered } ?: entries.first()
        val cleared = !entry.status.hasUsableCredentials
        renderResult(
            CommandResult(
                operation = "logout",
                ok = cleared,
                endpoint = resource,
                scopes = emptyList(),
                authServer = entry.issuer,
                status = entry.status,
                detail = when {
                    !entry.status.isRegistered -> "no client registration for this resource"
                    cleared -> "tokens revoked and cleared; registration kept"
                    else -> "tokens still present"
                },
            ),
            entry = entry,
            reveal = reveal,
        )
    }
}
