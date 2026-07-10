package de.gematik.zeta.cli.vsdm

import com.github.ajalt.clikt.core.Context
import de.gematik.zeta.cli.ZetaCliktCommand

/**
 * `zeta vsdm` — Versichertenstammdaten (VSD) operations. A container for the verbs; the read itself
 * lives in [VsdmGetCommand] (`zeta vsdm get`).
 */
class VsdmCommand : ZetaCliktCommand(name = "vsdm") {
    override fun help(context: Context) =
        "Read Versichertenstammdaten (VSD) from a PoPP token."
}
