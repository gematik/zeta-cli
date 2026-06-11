package de.gematik.zeta.cli.sdk

import de.gematik.zeta.cli.storage.zetaConfigDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * State exposed by the launcher about which zeta-sdk version it picked, plus helpers for
 * the `zeta sdk use` / `zeta sdk current` cli commands that mutate or read the sticky
 * pointer.
 *
 * The launcher (`de.gematik.zeta.launcher.LauncherKt`) sets these system properties
 * before invoking `cli.MainKt#main`:
 *   - `zeta.sdk.active`    — version actually loaded
 *   - `zeta.sdk.source`    — `flag` | `env` | `sticky` | `default`
 *   - `zeta.sdk.default`   — bundled default version
 *   - `zeta.sdk.available` — comma-separated list of bundled versions
 *   - `zeta.app.home`      — install root
 *
 * When the cli runs outside the launcher (e.g. `./zeta-dev`, `:cli:run`), the properties
 * are absent — `activeSdk()` and friends return `null` / empty list.
 */

private const val ACTIVE_FILENAME = "sdk"

internal fun activeSdk(): String? = System.getProperty("zeta.sdk.active")?.takeIf { it.isNotBlank() }

internal fun activeSdkSource(): String? = System.getProperty("zeta.sdk.source")?.takeIf { it.isNotBlank() }

internal fun defaultSdk(): String? = System.getProperty("zeta.sdk.default")?.takeIf { it.isNotBlank() }

internal fun availableSdks(): List<String> = System.getProperty("zeta.sdk.available")
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    .orEmpty()

/** `$XDG_CONFIG_HOME/telematik/zeta/sdk` — the sticky pointer the launcher reads. */
internal fun stickySdkFile(): Path = zetaConfigDir().resolve(ACTIVE_FILENAME)

internal fun readStickySdk(): String? {
    val file = stickySdkFile()
    if (!file.exists()) return null
    return runCatching { file.readText().trim().takeIf(String::isNotEmpty) }.getOrNull()
}

internal fun writeStickySdk(version: String) {
    val file = stickySdkFile()
    file.parent?.createDirectories()
    file.writeText(version + "\n")
}
