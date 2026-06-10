package de.gematik.zeta.cli.connector

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `.kon` file lookup, ported from the Go `ti` CLI.
 *
 * Resolution order for a name passed via `--connector-config`, `ZETA_CONNECTOR_CONFIG`,
 * the `active` file, or the default `"default"`:
 * 1. `~/...` is tilde-expanded to the user's home directory.
 * 2. **Path-like** (absolute or contains `/`): tried as-is, then with a `.kon` suffix.
 * 3. **Short name**: tried in `.`, then `./<name>.kon`, then `<config-home>/telematik/connectors/<name>.kon`,
 *    then `<config-home>/telematik/connectors/<name>`.
 *
 * macOS deliberately follows the Linux convention (`~/.config`) instead of Apple's
 * `~/Library/Application Support`, so the same .kon files work on Linux and macOS without
 * symlinks. Windows defaults to `%APPDATA%` (the platform convention for per-user roaming
 * application state) rather than `~/.config`. Override anywhere with `XDG_CONFIG_HOME`.
 */
private const val CONNECTORS_SUBDIR = "telematik/connectors"
private const val ACTIVE_FILENAME = "active"

private val isWindows: Boolean =
    System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true

/**
 * The base config directory the CLI persists state under. Resolution order:
 *  1. `$XDG_CONFIG_HOME` — explicit override on any OS.
 *  2. On Windows: `%APPDATA%` (typically `C:\Users\<you>\AppData\Roaming`).
 *  3. `~/.config` — Linux/macOS default; also the Windows fallback when `APPDATA` is
 *     somehow unset (rare).
 */
fun xdgConfigHome(): Path {
    System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { return Path(it) }
    if (isWindows) {
        System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { return Path(it) }
    }
    return Path(System.getProperty("user.home"), ".config")
}

/** `$XDG_CONFIG_HOME/telematik/connectors/`. Created on demand by callers; not by this function. */
fun connectorsConfigDir(): Path = xdgConfigHome().resolve(CONNECTORS_SUBDIR)

/** `$XDG_CONFIG_HOME/telematik/connectors/active` — pointer file for the sticky selection. */
fun activeConnectorFile(): Path = connectorsConfigDir().resolve(ACTIVE_FILENAME)

/**
 * Read the sticky connector selection from the `active` file. Returns `null` when the file
 * is missing, unreadable, or blank.
 */
fun readActiveConnector(): String? {
    val path = activeConnectorFile()
    if (!path.exists()) return null
    val name = runCatching { path.readText() }.getOrNull()?.trim().orEmpty()
    return name.ifEmpty { null }
}

/**
 * Persist [name] as the sticky connector selection. Creates the parent directory on demand
 * — `zeta connector use` is typically the first thing that touches it.
 */
fun writeActiveConnector(name: String) {
    val path = activeConnectorFile()
    path.parent?.createDirectories()
    path.writeText(name + "\n")
}

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

    val xdg = connectorsConfigDir()
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
    addFrom(connectorsConfigDir())
    return seen.toList()
}

class KonFileNotFoundException(message: String) : RuntimeException(message)
