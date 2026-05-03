package de.gematik.zeta.cli.get

import com.github.ajalt.clikt.core.Context
import de.gematik.zeta.cli.ZetaCliktCommand

class GetCommand : ZetaCliktCommand(name = "get") {
    override fun help(context: Context) = "Get Zeta-managed resources."
}
