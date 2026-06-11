package de.gematik.zeta.launcher

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.system.exitProcess

/**
 * The `zeta` binary's actual entry point. Resolves which bundled SDK version the user
 * wants, builds a `URLClassLoader` over the jars in `lib-cli/` plus the matching
 * `lib-sdk-<v>/`, and reflectively invokes `de.gematik.zeta.cli.MainKt#main` inside it.
 *
 * Strictly Kotlin stdlib + JDK — no JSON or Clikt — so the launcher jar stays tiny and
 * has zero version-compat surface of its own.
 */

private const val SDK_FLAG = "--sdk"
private const val SDK_ENV = "ZETA_SDK"
private const val ACTIVE_FILENAME = "sdk"
private const val DEFAULT_FILENAME = "default"
private const val LAUNCHER_DIR = "lib-launcher"
private const val CLI_DIR = "lib-cli"
private const val SDK_PARENT_DIR = "lib-sdk"
private const val SDK_DIR_PREFIX = "lib-sdk-"
private const val CLI_MAIN_CLASS = "de.gematik.zeta.cli.MainKt"

fun main(args: Array<String>) {
    val appHome = resolveAppHome()
    val available = discoverBundledSdks(appHome)
    if (available.isEmpty()) {
        System.err.println("zeta: no SDK versions bundled under $appHome/")
        exitProcess(2)
    }

    val (cliArgs, sdkFromFlag) = stripSdkFlag(args)
    val default = readDefault(appHome) ?: available.last()
    val (sdk, source) = resolveSdkVersion(
        flag = sdkFromFlag,
        env = System.getenv(SDK_ENV)?.takeIf(String::isNotBlank),
        sticky = readSticky(),
        default = default,
        available = available,
    )

    System.setProperty("zeta.app.home", appHome.toString())
    System.setProperty("zeta.sdk.active", sdk)
    System.setProperty("zeta.sdk.source", source)
    System.setProperty("zeta.sdk.default", default)
    System.setProperty("zeta.sdk.available", available.joinToString(","))

    // lib-cli URLs first so on basename collisions (e.g. an SDK pinning a different Ktor
    // patch) the cli's compiled-against version wins.
    val urls = classpathOf(appHome.resolve(CLI_DIR)) +
        classpathOf(appHome.resolve("$SDK_DIR_PREFIX$sdk"))
    val zetaLoader = URLClassLoader(urls, Launcher::class.java.classLoader)

    val mainClass = Class.forName(CLI_MAIN_CLASS, true, zetaLoader)
    val mainMethod = mainClass.getDeclaredMethod("main", Array<String>::class.java)
    Thread.currentThread().contextClassLoader = zetaLoader
    @Suppress("UNCHECKED_CAST")
    mainMethod.invoke(null, cliArgs as Any)
}

private object Launcher

/**
 * Locate the install root. The launcher jar sits at `<appHome>/lib-launcher/<launcher>.jar`,
 * so two `parent`s up from the jar URL is `<appHome>`. Falls back to the `zeta.app.home`
 * system property if set (useful for ad-hoc testing without an installed dist).
 */
private fun resolveAppHome(): Path {
    System.getProperty("zeta.app.home")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
    val codeSource = Launcher::class.java.protectionDomain?.codeSource?.location
        ?: error("zeta: cannot locate launcher jar (no protection-domain code source)")
    val jarPath = Paths.get(codeSource.toURI())
    return jarPath.parent?.parent
        ?: error("zeta: launcher jar at unexpected location: $jarPath")
}

private fun discoverBundledSdks(appHome: Path): List<String> =
    runCatching {
        Files.list(appHome).use { stream ->
            stream
                .filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .filter { it.startsWith(SDK_DIR_PREFIX) }
                .map { it.removePrefix(SDK_DIR_PREFIX) }
                .sorted()
                .toList()
        }
    }.getOrDefault(emptyList())

private fun readDefault(appHome: Path): String? {
    val file = appHome.resolve(SDK_PARENT_DIR).resolve(DEFAULT_FILENAME)
    return runCatching { file.readText().trim().takeIf(String::isNotEmpty) }.getOrNull()
}

private fun readSticky(): String? {
    val file = xdgConfigHome().resolve("telematik/zeta").resolve(ACTIVE_FILENAME)
    if (!file.exists() || !file.isRegularFile()) return null
    return runCatching { file.readText().trim().takeIf(String::isNotEmpty) }.getOrNull()
}

private val isWindows: Boolean =
    System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true

private fun xdgConfigHome(): Path {
    System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
    if (isWindows) {
        System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
    }
    return Paths.get(System.getProperty("user.home"), ".config")
}

/**
 * Pull `--sdk <value>` (or `--sdk=value`) out of the raw argv, returning the remaining
 * args plus the captured value if any. Mirrors the pre-Clikt sniff pattern in
 * `cli/.../Main.kt` for `--trace` / `--no-config`.
 */
internal fun stripSdkFlag(args: Array<String>): Pair<Array<String>, String?> {
    val rest = ArrayList<String>(args.size)
    var value: String? = null
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        when {
            arg == SDK_FLAG -> {
                if (i + 1 < args.size) {
                    value = args[i + 1]
                    i += 2
                } else {
                    System.err.println("zeta: $SDK_FLAG requires a version argument")
                    exitProcess(2)
                }
            }
            arg.startsWith("$SDK_FLAG=") -> {
                value = arg.removePrefix("$SDK_FLAG=")
                i += 1
            }
            else -> {
                rest.add(arg)
                i += 1
            }
        }
    }
    return rest.toTypedArray() to value?.takeIf { it.isNotBlank() }
}

internal fun resolveSdkVersion(
    flag: String?,
    env: String?,
    sticky: String?,
    default: String,
    available: List<String>,
): Pair<String, String> {
    val (chosen, source) = when {
        flag != null -> flag to "flag"
        env != null -> env to "env"
        sticky != null -> sticky to "sticky"
        else -> default to "default"
    }
    if (chosen !in available) {
        System.err.println(
            "zeta: unknown SDK version \"$chosen\" (source: $source); " +
                "available: ${available.joinToString(", ")}",
        )
        exitProcess(2)
    }
    return chosen to source
}

private fun classpathOf(dir: Path): Array<java.net.URL> {
    if (!dir.exists()) error("zeta: missing classpath directory $dir")
    return dir.listDirectoryEntries("*.jar")
        .filter { it.isRegularFile() }
        .sortedBy { it.name }
        .map { it.toUri().toURL() }
        .toTypedArray()
}
