package de.gematik.zeta.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.sources.ChainedValueSource
import de.gematik.zeta.cli.config.YamlValueSource
import de.gematik.zeta.cli.config.discoverZetaConfigFiles

class ZetaCommand : ZetaCliktCommand(name = "zeta") {
    init {
        // Install a YAML-backed value source so options can fall back to ./zeta.yaml or
        // $XDG_CONFIG_HOME/telematik/zeta/zeta.yaml. Cwd takes precedence over XDG via
        // [ChainedValueSource]. Files are parsed lazily on first use; absent files are
        // silently ignored. `${VAR}` placeholders in the file are expanded against the
        // process environment before parsing — same syntax as `.kon` files.
        context {
            val sources = discoverZetaConfigFiles().map(::YamlValueSource)
            valueSource = when (sources.size) {
                0 -> null
                1 -> sources.single()
                else -> ChainedValueSource(sources)
            }
        }
    }

    override fun help(context: Context) = "Swiss-army-knife for ZETA"
}
