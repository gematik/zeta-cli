package de.gematik.zeta.cli.connector

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import de.gematik.zeta.cli.ZetaCliktCommand

/**
 * `zeta connector use <name>` — record [activeConnectorFile] so subsequent commands without
 * `-c/--connector-config` or `ZETA_CONNECTOR_CONFIG` pick up the chosen `.kon`. Validates
 * the name resolves to a real file before writing, so a typo is rejected here rather than
 * deferred to the next command.
 */
class ConnectorUseCommand : ZetaCliktCommand(name = "use") {
    private val name: String by argument(
        name = "NAME",
        help = "Name of a .kon configuration discoverable by `zeta connector configs`.",
    )

    override fun help(context: Context) =
        "Set the active connector configuration. Stored at " +
            "\$XDG_CONFIG_HOME/telematik/connectors/active and consulted when " +
            "-c/--connector-config and ZETA_CONNECTOR_CONFIG are unset."

    override fun runCommand() {
        try {
            resolveKonFile(name)
        } catch (e: KonFileNotFoundException) {
            throw UsageError(e.message ?: "kon file not found")
        }
        writeActiveConnector(name)
        echo("active connector set to \"$name\"", err = true)
    }
}
