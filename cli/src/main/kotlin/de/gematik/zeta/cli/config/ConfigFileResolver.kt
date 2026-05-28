package de.gematik.zeta.cli.config

import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Resolve the single `zeta.yaml` file to load for this invocation.
 *
 * Precedence:
 *   1. [override] — the value of `-f/--file` or `ZETA_CONFIG`, pre-detected by `Main.kt`
 *      before Clikt parses. When set, the file MUST exist — a missing path is an explicit
 *      bug rather than a silent fallback, so we surface it.
 *   2. Auto-discovery via [discoverZetaConfigFile] — project-local `./zeta.yaml`, else
 *      `$XDG_CONFIG_HOME/telematik/zeta/zeta.yaml`. Either or both may legitimately be
 *      absent; in that case the function returns `null` and the CLI runs without a file.
 *
 * Throws [ConfigFileMissingException] when [override] is non-null and the file is absent.
 */
fun resolveConfigFile(override: Path?): Path? {
    if (override != null) {
        if (!override.exists()) throw ConfigFileMissingException(override)
        return override
    }
    return discoverZetaConfigFile()
}

class ConfigFileMissingException(val path: Path) :
    RuntimeException("config file not found: $path")
