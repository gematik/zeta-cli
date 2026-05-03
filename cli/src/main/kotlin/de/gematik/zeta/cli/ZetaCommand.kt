package de.gematik.zeta.cli

import com.github.ajalt.clikt.core.Context

class ZetaCommand : ZetaCliktCommand(name = "zeta") {
    override fun help(context: Context) = "Swiss-army-knife for ZETA"
}
