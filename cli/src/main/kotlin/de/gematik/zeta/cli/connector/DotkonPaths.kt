package de.gematik.zeta.cli.connector

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/**
 * `.kon` file lookup, ported from the Go `ti` CLI.
 *
 * Resolution order for a name passed via `--connector-config`, `ZETA_CONNECTOR_CONFIG`,
 * or the default `"default"`:
 * 1. `~/...` is tilde-expanded to the user's home directory.
 * 2. **Path-like** (absolute or contains `/`): tried as-is, then with a `.kon` suffix.
 * 3. **Short name**: tried in `.`, then `./<name>.kon`, then `$XDG_CONFIG_HOME/telematik/kon/<name>.kon`,
 *    then `$XDG_CONFIG_HOME/telematik/kon/<name>`.
 *
 * macOS deliberately follows the Linux convention (`~/.config`) instead of Apple's
 * `~/Library/Application Support`, so the same .kon files work on both platforms without
 * symlinks. Override with `XDG_CONFIG_HOME` if you really want a different location.
 */
private const val DOTKON_SUBDIR = "telematik/kon"

/** `$XDG_CONFIG_HOME` if set, otherwise `~/.config` — Linux semantics on every OS. */
fun xdgConfigHome(): Path =
    System.getenv("XDG_CONFIG_HOME")
        ?.takeIf { it.isNotBlank() }
        ?.let { Path(it) }
        ?: Path(System.getProperty("user.home"), ".config")

/** `$XDG_CONFIG_HOME/telematik/kon/`. Created on demand by callers; not by this function. */
fun konConfigDir(): Path = xdgConfigHome().resolve(DOTKON_SUBDIR)

/**
 * Resolve a [name] to a `.kon` file path. See the file-level KDoc for the search rules.
 *
 * @throws KonFileNotFoundException when no candidate exists.
 */
fun resolveKonFile(name: String): Path {
    val expanded = if (name.startsWith("~/")) {
        Path(System.getProperty("user.home"), name.removePrefix("~/"))
    } else {
        null
    }
    val candidate = expanded?.toString() ?: name

    // Path-like inputs: absolute, tilde-expanded, or containing a separator.
    val isPathLike = expanded != null || Path(candidate).isAbsolute || '/' in candidate
    if (isPathLike) {
        val asIs = Path(candidate)
        if (asIs.exists()) return asIs
        val withExt = Path("$candidate.kon")
        if (withExt.exists()) return withExt
        throw KonFileNotFoundException("configuration file not found: $candidate")
    }

    // Short name: search current directory then XDG config directory.
    Path(name).takeIf { it.exists() }?.let { return it }
    Path("$name.kon").takeIf { it.exists() }?.let { return it }

    val xdg = konConfigDir()
    xdg.resolve("$name.kon").takeIf { it.exists() }?.let { return it }
    xdg.resolve(name).takeIf { it.exists() }?.let { return it }

    throw KonFileNotFoundException(
        "configuration \"$name\" not found (searched current directory and $xdg/)",
    )
}

/**
 * Every `.kon` file discoverable in the current directory followed by the XDG config
 * directory, deduplicated by absolute path so a symlink doesn't show twice.
 */
fun listKonConfigs(): List<Path> {
    val seen = LinkedHashSet<Path>()
    fun addFrom(dir: Path) {
        runCatching {
            dir.listDirectoryEntries("*.kon")
                .filter { it.isRegularFile() }
                .sortedBy { it.fileName.toString() }
                .forEach { seen.add(it.toAbsolutePath()) }
        }
    }
    addFrom(Path("."))
    addFrom(konConfigDir())
    return seen.toList()
}

class KonFileNotFoundException(message: String) : RuntimeException(message)
