package de.gematik.zeta.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.context
import de.gematik.zeta.cli.config.YamlValueSource
import de.gematik.zeta.cli.config.discoverZetaConfigFile

class ZetaCommand : ZetaCliktCommand(name = "zeta") {
    init {
        // Install a YAML-backed value source so options can fall back to ./zeta.yaml
        // (project-local) or $XDG_CONFIG_HOME/telematik/zeta/zeta.yaml (user-global) —
        // exactly one of the two, never merged. Cwd wins outright when present. The file
        // is parsed lazily on first use; absent files are silently ignored. `${VAR}`
        // placeholders are expanded against the process environment before parsing —
        // same syntax as `.kon` files.
        context {
            valueSource = discoverZetaConfigFile()?.let(::YamlValueSource)
        }
    }

    override fun help(context: Context) = "Swiss-army-knife for ZETA"
}
