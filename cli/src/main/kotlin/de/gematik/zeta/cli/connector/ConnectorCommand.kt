package de.gematik.zeta.cli.connector

import com.github.ajalt.clikt.core.Context
import de.gematik.zeta.cli.ZetaCliktCommand

/**
 * Parent command for Connector operations.
 *
 * The active `.kon` file is selected via the global sticky option `--connector-config`
 * (env: `ZETA_CONNECTOR_CONFIG`, default `"default"`); see [ZetaCliktCommand].
 */
class ConnectorCommand : ZetaCliktCommand(name = "connector") {
    override fun help(context: Context) =
        "Talk to a gematik Connector described by a .kon configuration file."
}
