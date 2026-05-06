package de.gematik.zeta.cli.popp

import com.github.ajalt.clikt.core.Context
import de.gematik.zeta.cli.ZetaCliktCommand

class PoppCommand : ZetaCliktCommand(name = "popp") {
    override fun help(context: Context) =
        "Retrieve a Proof-of-Patient-Presence (PoPP) token via different scenarios."
}
