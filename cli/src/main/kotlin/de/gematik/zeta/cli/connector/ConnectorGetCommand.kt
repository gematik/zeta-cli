package de.gematik.zeta.cli.connector

import com.github.ajalt.clikt.core.Context
import de.gematik.zeta.cli.ZetaCliktCommand

class ConnectorGetCommand : ZetaCliktCommand(name = "get") {
    override fun help(context: Context) = "Query the Connector for live state."
}
