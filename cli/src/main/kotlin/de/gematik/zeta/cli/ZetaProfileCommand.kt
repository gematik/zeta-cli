package de.gematik.zeta.cli

import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import de.gematik.zeta.cli.output.OutputFormat
import de.gematik.zeta.cli.output.renderJson
import de.gematik.zeta.cli.state.CommandResult
import de.gematik.zeta.cli.state.Entry
import de.gematik.zeta.cli.state.ProfileStores
import de.gematik.zeta.cli.state.entryFor
import de.gematik.zeta.cli.state.renderEntryJson
import de.gematik.zeta.cli.state.renderEntryText
import de.gematik.zeta.cli.state.renderResultJson
import de.gematik.zeta.cli.state.renderResultText
import de.gematik.zeta.cli.storage.ProfileDb
import de.gematik.zeta.cli.storage.zetaProfilePath
import de.gematik.zeta.sdk.SdkStatus
import kotlinx.coroutines.runBlocking

/**
 * Base for any command that scopes its work to a single named storage profile but does NOT
 * need an authenticated session (no Connector / no PKCS#12). Used by read-only commands
 * (`status`, `inspect` without `--no-cache`) and for local-only mutations (`forget`).
 *
 * Auth-requiring lifecycle commands extend [de.gematik.zeta.cli.client.ZetaSessionCommand]
 * instead, which adds the Connector / PKCS#12 option groups on top of `--profile`.
 *
 * Also hosts two small render helpers shared by every command that wants to print the
 * "after picture" for a single resource: `status URL`, plus each lifecycle command once it
 * has finished a successful SDK call. The store lookup mirrors the SDK's own bug-for-bug
 * status logic (see [entryFor]).
 */
abstract class ZetaProfileCommand(name: String) : ZetaCliktCommand(name = name) {
    protected val profile: String by option(
        "--profile",
        metavar = "NAME",
        envvar = "ZETA_PROFILE",
        help = "Storage profile name. SDK state (registration, tokens, …) is persisted to " +
            "\$XDG_CONFIG_HOME/telematik/zeta/<profile>.storage.db. (env: ZETA_PROFILE)",
    ).default("default")

    /**
     * Load the current [Entry] for [resource] (an origin URL like `https://example.com/`).
     * Resolves the most recently touched scope recorded for that resource — which is exactly the
     * one a lifecycle command just wrote. Always returns an Entry: a resource the profile has never
     * seen yields `NOT_REGISTERED` with everything else null.
     */
    internal fun loadEntry(resource: String): Entry {
        val db = ProfileDb(zetaProfilePath(profile))
        val scope = db.latestContextFor(resource)
            ?: return Entry(resource, null, SdkStatus.NOT_REGISTERED, null, null)
        return runBlocking { entryFor(ProfileStores(scope, db)) }
    }

    /** Print [entry] in the user-selected output format. `reveal` exposes secrets. */
    internal fun renderEntry(entry: Entry, reveal: Boolean) {
        when (cliConfig.outputFormat) {
            OutputFormat.JSON -> echo(renderJson(renderEntryJson(entry, reveal), colorize = colorize))
            OutputFormat.TEXT, OutputFormat.RAW -> echo(renderEntryText(entry, colorize, reveal))
        }
    }

    /**
     * Print the compact pass/fail [result] for a lifecycle command. In text mode `--reveal`
     * appends the full [entry] detail (secrets included) below the verdict; in JSON mode only the
     * structured result is emitted (use `status --reveal` for the full picture).
     */
    internal fun renderResult(result: CommandResult, entry: Entry? = null, reveal: Boolean = false) {
        when (cliConfig.outputFormat) {
            OutputFormat.JSON -> echo(renderJson(renderResultJson(result), colorize = colorize))
            OutputFormat.TEXT, OutputFormat.RAW -> {
                echo(renderResultText(result, colorize))
                if (reveal && entry != null) {
                    echo("")
                    echo(renderEntryText(entry, colorize, reveal = true))
                }
            }
        }
    }
}
