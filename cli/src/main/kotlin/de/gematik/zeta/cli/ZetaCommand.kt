package de.gematik.zeta.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.context
import de.gematik.zeta.cli.config.YamlValueSource
import java.nio.file.Path

class ZetaCommand(private val configPath: Path? = null) : ZetaCliktCommand(name = "zeta") {
    init {
        // Install a YAML-backed value source so options can fall back to a config file.
        // The path was already resolved (and existence-checked when explicit) in Main.kt
        // via `resolveConfigFile`; here we only need to attach it to the Clikt context.
        // This block must stay side-effect-free — Clikt's error handler re-builds the
        // context to format help, so a throw here triggers a re-entrant throw that
        // bypasses the clean error printer.
        context {
            valueSource = configPath?.let(::YamlValueSource)
        }
    }

    override fun help(context: Context) = "Swiss-army-knife for ZETA"
}
