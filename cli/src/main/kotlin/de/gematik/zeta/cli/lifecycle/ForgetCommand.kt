package de.gematik.zeta.cli.lifecycle

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import de.gematik.zeta.cli.ZetaProfileCommand
import de.gematik.zeta.cli.client.originOf
import de.gematik.zeta.cli.sdk.NoopSubjectTokenProvider
import de.gematik.zeta.cli.sdk.buildZetaSdkClient
import de.gematik.zeta.cli.storage.zetaProfilePath
import de.gematik.zeta.sdk.ZetaSdk.forget
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import kotlin.io.path.exists
import kotlinx.coroutines.runBlocking

private val log = KotlinLogging.logger {}

/**
 * `zeta forget [URL]` / `zeta forget --all` — wipe SDK state for one resource or the
 * entire profile. No network calls; pure storage mutation.
 *
 *  - **`zeta forget URL`**: drive `ZetaSdk.forget(client)` so the SDK clears its own
 *    bookkeeping for this resource. The client is built with [NoopSubjectTokenProvider]
 *    because forget never calls `createSubjectToken` — auth options are not required, even
 *    though `forget` is a write. Loud failure if the assumption breaks.
 *  - **`zeta forget --all`**: delete the entire profile storage file. No SDK build at all.
 *
 * Confirmation: interactive prompt by default, suppressed by `--force`. In a non-TTY
 * shell (piped, scripted) without `--force` we refuse rather than block or assume yes —
 * scripts that genuinely want to wipe state must opt in explicitly.
 */
class ForgetCommand : ZetaProfileCommand(name = "forget") {
    private val url: String? by argument(
        name = "URL",
        help = "URL of the Zeta-protected resource to forget. Omit with --all to wipe the whole profile.",
    ).optional()

    private val all: Boolean by option(
        "--all",
        help = "Wipe the entire profile storage file. Cannot be combined with a URL.",
    ).flag(default = false)

    private val force: Boolean by option(
        "--force",
        help = "Skip the interactive confirmation prompt. Required in non-interactive (scripted) mode.",
    ).flag(default = false)

    override fun help(context: Context) =
        "Wipe SDK state for one resource (`forget URL`) or the whole profile (`forget --all`)."

    override fun runCommand() {
        val target = url
        when {
            target != null && all -> throw UsageError("Pass a URL or --all, not both.")
            target == null && !all -> throw UsageError(
                "Choose one:\n" +
                    "  • zeta forget URL    — wipe state for a single resource\n" +
                    "  • zeta forget --all  — wipe the entire profile",
            )
            all -> forgetAll()
            else -> forgetResource(target!!)
        }
    }

    private fun forgetResource(rawUrl: String) {
        val resource = originOf(rawUrl)
        if (!confirm("Forget all cached state for $resource in profile '$profile'?")) return

        log.info { "Forgetting $resource via SDK" }
        val sdk = buildZetaSdkClient(
            resource = resource,
            scopes = emptyList(),
            storagePath = zetaProfilePath(profile),
            tokenProvider = NoopSubjectTokenProvider,
            cliConfig = cliConfig,
        )
        runBlocking { sdk.forget().getOrThrow() }
        echo("Forgot $resource (profile: $profile).")
    }

    private fun forgetAll() {
        val path = zetaProfilePath(profile)
        if (!path.exists()) {
            echo("Profile '$profile' has no storage file at $path. Nothing to forget.")
            return
        }
        if (!confirm("Delete the entire profile storage at $path?")) return

        log.info { "Deleting profile storage at $path" }
        Files.delete(path)
        echo("Deleted $path.")
    }

    /**
     * `--force` → always proceed. Non-interactive shell without `--force` → refuse loudly.
     * Interactive: prompt, default No. Treat anything other than a leading 'y' (case-
     * insensitive) as cancel.
     */
    private fun confirm(question: String): Boolean {
        if (force) return true
        if (!currentContext.terminal.terminalInfo.inputInteractive) {
            throw CliktError(
                "Refusing to wipe state in non-interactive mode without --force. " +
                    "Re-run with --force to confirm.",
            )
        }
        echo("$question [y/N] ", trailingNewline = false)
        val response = readlnOrNull()?.trim()?.lowercase().orEmpty()
        if (response != "y" && response != "yes") {
            echo("Aborted.")
            return false
        }
        return true
    }
}
