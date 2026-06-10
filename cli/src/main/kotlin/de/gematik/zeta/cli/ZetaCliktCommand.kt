package de.gematik.zeta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.findObject
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.counted
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.rendering.AnsiLevel
import de.gematik.zeta.cli.http.parseProxyConfig
import de.gematik.zeta.cli.output.OutputFormat
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

abstract class ZetaCliktCommand(name: String? = null) : CliktCommand(name = name) {
    private val verbose: Int by option(
        "-v", "--verbose",
        help = "Increase log verbosity. Repeat for more detail (-v INFO, -vv DEBUG, -vvv TRACE).",
    ).counted()

    private val connectTimeoutOpt: Duration? by option(
        "--connect-timeout",
        metavar = "SECONDS",
        envvar = "ZETA_CONNECT_TIMEOUT",
        help = "TCP connection timeout in seconds. Default: 5. (env: ZETA_CONNECT_TIMEOUT)",
    ).convert { it.toLong().seconds }

    private val requestTimeoutOpt: Duration? by option(
        "--request-timeout",
        metavar = "SECONDS",
        envvar = "ZETA_REQUEST_TIMEOUT",
        help = "Total HTTP request timeout in seconds. Default: 30. (env: ZETA_REQUEST_TIMEOUT)",
    ).convert { it.toLong().seconds }

    private val insecure: Boolean by option(
        "-k", "--insecure",
        envvar = "ZETA_INSECURE",
        help = "Disable TLS certificate verification. Dangerous — use only for testing. " +
            "(env: ZETA_INSECURE)",
    ).flag(default = false)

    private val aslProd: Boolean by option(
        "--asl-prod",
        envvar = "ZETA_ASL_PROD",
        help = "Use the ASL (Attestation Service Local) production environment instead of " +
            "the non-prod default. (env: ZETA_ASL_PROD)",
    ).flag(default = false)

    private val caCertFiles: List<Path> by option(
        "--ca-cert",
        metavar = "FILE",
        envvar = "ZETA_CA_CERT",
        help = "Add a CA certificate (PEM) to the trust store. Default JVM roots are kept. " +
            "Repeat the flag for multiple files; the env var supplies a single file. " +
            "(env: ZETA_CA_CERT)",
    ).path(mustExist = true, canBeFile = true, canBeDir = false).multiple()

    private val outputFormatOpt: OutputFormat? by option(
        "-o", "--output-format",
        metavar = "FORMAT",
        envvar = "ZETA_OUTPUT_FORMAT",
        help = "Output format: text (default), json, or raw. (env: ZETA_OUTPUT_FORMAT)",
    ).enum<OutputFormat>(ignoreCase = true)

    private val connectorConfigOpt: String? by option(
        "-c", "--connector-config",
        metavar = "NAME",
        envvar = "ZETA_CONNECTOR_CONFIG",
        help = "Name or path of a .kon (Connector) configuration file. Resolved as: " +
            "tilde-expanded path, exact path, <name>.kon in current dir, then " +
            "\$XDG_CONFIG_HOME/telematik/connectors/<name>.kon. When unset, falls back to " +
            "the `active` file written by `zeta connector use`, then to 'default'. " +
            "(env: ZETA_CONNECTOR_CONFIG)",
    )

    private val proxyUrlOpt: String? by option(
        "--proxy",
        metavar = "URL",
        envvar = "ZETA_PROXY",
        help = "HTTP forward proxy for every outbound connection. URL form " +
            "'http[s]://[user:pass@]host[:port]'. (env: ZETA_PROXY)",
    )

    private val proxyUserOpt: String? by option(
        "--proxy-user",
        metavar = "USER",
        envvar = "ZETA_PROXY_USER",
        help = "Proxy username. Overrides any user-info embedded in --proxy. (env: ZETA_PROXY_USER)",
    )

    private val proxyPasswordOpt: String? by option(
        "--proxy-password",
        metavar = "PASSWORD",
        envvar = "ZETA_PROXY_PASSWORD",
        help = "Proxy password. Overrides any user-info embedded in --proxy. (env: ZETA_PROXY_PASSWORD)",
    )

    // Declared so Clikt accepts `--trace` at any depth and lists it in --help.
    // The real activation happens in Main.kt before Clikt parses, so the SDK init / root
    // span open before any subcommand runs. Reading this option here is therefore only for
    // help-text / value-source completeness; we never branch on it.
    @Suppress("unused")
    private val trace: Boolean by option(
        "--trace",
        envvar = "ZETA_TRACE",
        help = "Print an in-process span tree at end of command. (env: ZETA_TRACE)",
    ).flag(default = false)

    // Same pattern as --trace: parsed in Main.kt before Clikt runs (so the path can be
    // wired into the Clikt context's value source). Declared here only so Clikt accepts
    // it at any depth and lists it in --help. Never read.
    @Suppress("unused")
    private val configFile: Path? by option(
        "-f", "--file",
        metavar = "FILE",
        envvar = "ZETA_CONFIG",
        help = "Path to a zeta.yaml-format config file. Overrides project-local " +
            "./zeta.yaml and the XDG-global default. (env: ZETA_CONFIG)",
    ).path(mustExist = false, canBeFile = true, canBeDir = false)

    // Same pre-Clikt detection pattern as --trace / -f. Declared here for help-text and
    // sticky parse acceptance only; the real activation happens in Main.kt.
    @Suppress("unused")
    private val noConfig: Boolean by option(
        "--no-config",
        envvar = "ZETA_NO_CONFIG",
        help = "Ignore zeta.yaml entirely. Skips -f/--file, ZETA_CONFIG, project-local " +
            "./zeta.yaml, and the XDG default — built-in defaults only. Mutually " +
            "exclusive with -f/--file. (env: ZETA_NO_CONFIG)",
    ).flag(default = false)

    /** Shared, lazily-built CLI configuration available to subcommands' `runCommand`. */
    internal val cliConfig: CliConfig
        get() = currentContext.findObject<CliConfig>()
            ?: error("CliConfig not set on the Clikt context — base run() must execute first.")

    /**
     * Whether to emit ANSI colour escapes. Honours TTY detection plus standard
     * `FORCE_COLOR` / `NO_COLOR` overrides via Mordant's terminal info.
     */
    internal val colorize: Boolean
        get() = currentContext.terminal.terminalInfo.ansiLevel != AnsiLevel.NONE

    final override fun run() {
        val config = currentContext.findOrSetObject { CliConfig() }
        if (verbose > 0) config.verbose = maxOf(config.verbose, verbose)
        if (insecure) config.insecure = true
        if (aslProd) config.aslProdEnvironment = true
        if (caCertFiles.isNotEmpty()) config.caCertFiles = config.caCertFiles + caCertFiles
        connectTimeoutOpt?.let { config.connectTimeout = it }
        requestTimeoutOpt?.let { config.requestTimeout = it }
        outputFormatOpt?.let { config.outputFormat = it }
        connectorConfigOpt?.let { config.connectorConfig = it }
        val proxyUrl = proxyUrlOpt?.takeIf { it.isNotBlank() }
        if (proxyUrl != null) {
            config.proxy = parseProxyConfig(proxyUrl, proxyUserOpt, proxyPasswordOpt)
        } else if (proxyUserOpt != null || proxyPasswordOpt != null) {
            throw UsageError("--proxy-user / --proxy-password require --proxy URL")
        }

        Logging.applyVerbosity(config.verbose)
        // Once per invocation, fire two startup lines on the leaf command:
        //   - INFO: zeta-cli + zeta-sdk versions, so -v traces self-identify which build
        //     produced them
        //   - DEBUG: echo argv as "zeta …" so -vv shows exactly what was run
        // Gating on `invokedSubcommand == null` keeps it to the leaf — without that, nested
        // commands (e.g. `connector get cards`) would each emit it.
        if (currentContext.invokedSubcommand == null) {
            invocationLog.info { "zeta-cli ${BuildConfig.VERSION}, zeta-sdk ${BuildConfig.ZETA_SDK_VERSION}" }
            invocationLog.debug { "zeta " + invocationArgs.joinToString(" ", transform = ::shellQuote) }
        }
        runCommand()
    }

    protected open fun runCommand(): Unit = Unit
}

private val invocationLog = KotlinLogging.logger("de.gematik.zeta.cli")

/**
 * POSIX-safe quoting: bareword if every char is shell-neutral, otherwise single-quoted with
 * embedded single quotes escaped as `'\''`. Empty string → `''`. Matches how `set -x` /
 * `xtrace` would render the argv.
 */
private fun shellQuote(s: String): String {
    if (s.isEmpty()) return "''"
    val safe = s.all { it.isLetterOrDigit() || it in "@%+=:,./-_" }
    return if (safe) s else "'" + s.replace("'", "'\\''") + "'"
}
