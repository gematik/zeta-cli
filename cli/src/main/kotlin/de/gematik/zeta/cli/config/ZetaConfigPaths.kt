package de.gematik.zeta.cli.config

import de.gematik.zeta.cli.storage.zetaConfigDir
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists

/** Filename used for both the project-local and user-global config files. */
const val ZETA_TOML_FILENAME = "zeta.toml"

/**
 * `$XDG_CONFIG_HOME/telematik/zeta/zeta.toml` on every OS — same XDG semantics as the
 * `.kon` lookup in [de.gematik.zeta.cli.connector.DotkonPaths]. macOS deliberately follows
 * the Linux convention (`~/.config`) over `~/Library/Application Support` so the same file
 * works on both platforms; override with `XDG_CONFIG_HOME` if you really want elsewhere.
 */
fun zetaXdgConfigFile(): Path = zetaConfigDir().resolve(ZETA_TOML_FILENAME)

/** `./zeta.toml` if it exists in the current working directory, otherwise `null`. */
fun zetaCwdConfigFile(): Path? = Path(ZETA_TOML_FILENAME).takeIf { it.exists() }

/**
 * `zeta.toml` files in lookup order: cwd first (project-local override), XDG second
 * (user-global default). Either or both may be absent — callers handle the empty list
 * by skipping config-file support.
 */
fun discoverZetaConfigFiles(): List<Path> = buildList {
    zetaCwdConfigFile()?.let { add(it) }
    zetaXdgConfigFile().takeIf { it.exists() }?.let { add(it) }
}
