package de.gematik.zeta.cli.connector

import com.github.ajalt.clikt.core.UsageError
import de.gematik.zeta.cli.CliConfig
import java.nio.file.Path

/**
 * Outcome of the four-step lookup for which `.kon` configuration to use, mirroring the Go
 * `ti` CLI: explicit flag/env → `active` file → `"default"`.
 */
internal data class ConnectorConfigSelection(val name: String, val source: Source) {
    enum class Source { FLAG_OR_ENV, ACTIVE_FILE, DEFAULT }
}

/**
 * Resolve which `.kon` short name (or path) to feed to [resolveKonFile]. Reads the
 * `active` pointer file only when no explicit flag/env value is present.
 */
internal fun CliConfig.selectConnectorConfig(): ConnectorConfigSelection {
    connectorConfig?.let { return ConnectorConfigSelection(it, ConnectorConfigSelection.Source.FLAG_OR_ENV) }
    readActiveConnector()?.let { return ConnectorConfigSelection(it, ConnectorConfigSelection.Source.ACTIVE_FILE) }
    return ConnectorConfigSelection("default", ConnectorConfigSelection.Source.DEFAULT)
}

/**
 * Resolve [CliConfig.connectorConfig] (with `active`-file / default fall-back) to a `.kon`
 * file path. Throws a [UsageError] with an actionable hint when the `active` file points at
 * a name that no longer resolves.
 */
internal fun CliConfig.resolveSelectedKonFile(): Path {
    val selection = selectConnectorConfig()
    return try {
        resolveKonFile(selection.name)
    } catch (e: KonFileNotFoundException) {
        val base = e.message ?: "kon file not found"
        val hint = if (selection.source == ConnectorConfigSelection.Source.ACTIVE_FILE) {
            "\n\nThe active connector points at \"${selection.name}\" which no longer resolves. " +
                "Run `zeta connector use <name>` to pick another, or `zeta connector configs` " +
                "to list available configs."
        } else ""
        throw UsageError(base + hint)
    }
}
