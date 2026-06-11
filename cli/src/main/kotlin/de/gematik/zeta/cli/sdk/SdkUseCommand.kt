package de.gematik.zeta.cli.sdk

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import de.gematik.zeta.cli.ZetaCliktCommand

/**
 * `zeta sdk use <ver>` — record the version in `$XDG_CONFIG_HOME/telematik/zeta/sdk` so
 * the launcher picks it up when no `--sdk` flag or `ZETA_SDK` env var is set. Validates
 * against the bundled-versions list exported by the launcher so a typo is caught here
 * rather than on the next invocation.
 *
 * Mirrors `zeta connector use` (see ConnectorUseCommand.kt).
 */
class SdkUseCommand : ZetaCliktCommand(name = "use") {
    private val version: String by argument(
        name = "VERSION",
        help = "Bundled zeta-sdk version (see `zeta sdk list`).",
    )

    override fun help(context: Context) =
        "Set the sticky zeta-sdk version. Stored at \$XDG_CONFIG_HOME/telematik/zeta/sdk " +
            "and consulted by the launcher when --sdk and ZETA_SDK are unset."

    override fun runCommand() {
        val available = availableSdks()
        if (available.isEmpty()) {
            throw UsageError(
                "no bundled SDK versions detected — `zeta sdk use` only works from the " +
                    "launcher distribution.",
            )
        }
        if (version !in available) {
            throw UsageError(
                "unknown SDK version \"$version\"; available: ${available.joinToString(", ")}.",
            )
        }
        writeStickySdk(version)
        echo("active SDK set to \"$version\"", err = true)
    }
}
