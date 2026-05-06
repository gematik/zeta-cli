package de.gematik.zeta.cli

import com.github.ajalt.clikt.core.CliktCommand
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
import de.gematik.zeta.cli.output.OutputFormat
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
        help = "TCP connection timeout in seconds (default: 5).",
    ).convert { it.toLong().seconds }

    private val requestTimeoutOpt: Duration? by option(
        "--request-timeout",
        metavar = "SECONDS",
        help = "Total HTTP request timeout in seconds (default: 30).",
    ).convert { it.toLong().seconds }

    private val insecure: Boolean by option(
        "-k", "--insecure",
        help = "Disable TLS certificate verification. Dangerous — use only for testing.",
    ).flag(default = false)

    private val caCertFiles: List<Path> by option(
        "--ca-cert",
        metavar = "FILE",
        help = "Add a CA certificate (PEM) to the trust store. Default JVM roots are kept. Repeat for multiple files.",
    ).path(mustExist = true, canBeFile = true, canBeDir = false).multiple()

    private val outputFormatOpt: OutputFormat? by option(
        "-o", "--output-format",
        metavar = "FORMAT",
        help = "Output format: text (default) or json.",
    ).enum<OutputFormat>(ignoreCase = true)

    private val connectorConfigOpt: String? by option(
        "--connector-config",
        metavar = "NAME",
        envvar = "ZETA_CONNECTOR_CONFIG",
        help = "Name or path of a .kon (Connector) configuration file. Resolved as: " +
            "tilde-expanded path, exact path, <name>.kon in current dir, then " +
            "\$XDG_CONFIG_HOME/telematik/kon/<name>.kon. Default: 'default'. " +
            "(env: ZETA_CONNECTOR_CONFIG)",
    )

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
        if (caCertFiles.isNotEmpty()) config.caCertFiles = config.caCertFiles + caCertFiles
        connectTimeoutOpt?.let { config.connectTimeout = it }
        requestTimeoutOpt?.let { config.requestTimeout = it }
        outputFormatOpt?.let { config.outputFormat = it }
        connectorConfigOpt?.let { config.connectorConfig = it }

        Logging.applyVerbosity(config.verbose)
        runCommand()
    }

    protected open fun runCommand(): Unit = Unit
}
