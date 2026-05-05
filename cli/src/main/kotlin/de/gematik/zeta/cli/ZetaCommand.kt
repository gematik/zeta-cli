package de.gematik.zeta.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.sources.ChainedValueSource
import de.gematik.zeta.cli.config.TomlValueSource
import de.gematik.zeta.cli.config.discoverZetaConfigFiles

class ZetaCommand : ZetaCliktCommand(name = "zeta") {
    init {
        // Install a TOML-backed value source so options can fall back to ./zeta.toml or
        // $XDG_CONFIG_HOME/telematik/zeta/zeta.toml. Cwd takes precedence over XDG via
        // [ChainedValueSource]. Files are parsed lazily on first use; absent files are
        // silently ignored.
        context {
            val sources = discoverZetaConfigFiles().map(::TomlValueSource)
            valueSource = when (sources.size) {
                0 -> null
                1 -> sources.single()
                else -> ChainedValueSource(sources)
            }
        }
    }

    override fun help(context: Context) = "Swiss-army-knife for ZETA"
}
