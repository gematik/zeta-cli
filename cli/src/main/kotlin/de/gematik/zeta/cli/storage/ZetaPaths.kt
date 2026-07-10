package de.gematik.zeta.cli.storage

import de.gematik.zeta.cli.connector.xdgConfigHome
import java.nio.file.Path

/**
 * Paths for zeta CLI runtime state — token caches, registration data, anything the SDK
 * persists via [de.gematik.zeta.sdk.storage.SdkStorage].
 *
 * Layout matches the kon-files directory: `$XDG_CONFIG_HOME/telematik/<app>/`. macOS
 * uses `~/.config` like Linux (deliberately, not Apple's `~/Library/Application Support`)
 * so identical files work on both platforms — see [xdgConfigHome].
 */
private const val ZETA_SUBDIR = "telematik/zeta"

/** `$XDG_CONFIG_HOME/telematik/zeta/`. Created on demand by storage writers, not here. */
fun zetaConfigDir(): Path = xdgConfigHome().resolve(ZETA_SUBDIR)

/**
 * Path to the SQLite state database for a named profile.
 *
 * `<zetaConfigDir>/<profile>.storage.db`. The compound `.storage.db` suffix leaves room for
 * other files (configuration, exports, transient state) to coexist in the same directory
 * without name collisions. Multiple profiles let users separate state per Connector / per
 * environment without colliding; pair with the `.kon` profile name for a one-to-one mapping.
 */
fun zetaProfilePath(profile: String): Path = zetaConfigDir().resolve("$profile.storage.db")
