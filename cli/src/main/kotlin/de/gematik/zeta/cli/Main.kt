package de.gematik.zeta.cli

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import de.gematik.zeta.cli.client.HttpCommand
import de.gematik.zeta.cli.client.WsCommand
import de.gematik.zeta.cli.config.ConfigFileMissingException
import de.gematik.zeta.cli.config.resolveConfigFile
import de.gematik.zeta.cli.connector.ConnectorCommand
import de.gematik.zeta.cli.connector.ConnectorConfigsCommand
import de.gematik.zeta.cli.connector.ConnectorGetCardsCommand
import de.gematik.zeta.cli.connector.ConnectorGetCommand
import de.gematik.zeta.cli.connector.ConnectorInspectCommand
import de.gematik.zeta.cli.connector.ConnectorUseCommand
import de.gematik.zeta.cli.lifecycle.AuthenticateCommand
import de.gematik.zeta.cli.lifecycle.DiscoverCommand
import de.gematik.zeta.cli.lifecycle.ForgetCommand
import de.gematik.zeta.cli.lifecycle.LoginCommand
import de.gematik.zeta.cli.lifecycle.LogoutCommand
import de.gematik.zeta.cli.lifecycle.RegisterCommand
import de.gematik.zeta.cli.popp.PoppCommand
import de.gematik.zeta.cli.popp.PoppConnectorCommand
import de.gematik.zeta.cli.popp.PoppKartosCommand
import de.gematik.zeta.cli.state.StatusCommand
import de.gematik.zeta.cli.term.StderrColors
import de.gematik.zeta.stress.stressCommand
import de.gematik.zeta.cli.trace.Tracer
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.system.exitProcess

/**
 * The raw argv from [main], stashed so [ZetaCliktCommand] can echo it once at DEBUG —
 * `zeta <args…>` — to make "what command actually ran" obvious in `-vv` traces.
 */
internal var invocationArgs: Array<String> = emptyArray()
    private set

fun main(args: Array<String>) {
    invocationArgs = args
    // Must run before *any* SLF4J access — Logback resolves `${zeta.stderr.pattern:-…}` at
    // config-load time, which is triggered by the first `LoggerFactory.getLogger` call. If
    // we let a top-level `val log = KotlinLogging.logger {}` initialize before this line,
    // Logback locks in the coloured fallback and later `setProperty` calls have no effect
    // (so `NO_COLOR=1` would not disable colours in redirected output).
    if (!StderrColors.enabled) {
        System.setProperty("zeta.stderr.pattern", "%-5level %logger{24} - %msg%n")
    }
    val log = KotlinLogging.logger("de.gematik.zeta.cli.Main")

    // Pre-detect --trace so the tracer is initialised before the root span opens. Clikt
    // doesn't parse the flag until ZetaCliktCommand.run(), which is too late — by then
    // we're already inside the Tracer.root block. The flag is declared on
    // ZetaCliktCommand for help-text + sticky-at-any-depth parsing.
    if (args.any { it == "--trace" } || System.getenv("ZETA_TRACE")?.lowercase() in setOf("1", "true", "yes")) {
        Tracer.init()
    }

    // Pre-detect -f/--file (env: ZETA_CONFIG) and --no-config (env: ZETA_NO_CONFIG) for the
    // same reason as --trace above: the resolved path drives which YAML file
    // YamlValueSource reads, wired into the Clikt context at ZetaCommand construction
    // (before parse). Resolved here once so the existence check doesn't run inside the
    // Clikt `context { }` lambda, which gets re-invoked by Clikt's own error/help formatter
    // and would re-throw.
    val noConfig = args.any { it == "--no-config" } ||
        System.getenv("ZETA_NO_CONFIG")?.lowercase() in setOf("1", "true", "yes")
    val configOverride = (sniffOptValue(args, "-f", "--file")
        ?: System.getenv("ZETA_CONFIG")?.takeIf(String::isNotBlank))
        ?.let { Path(it) }
    if (noConfig && configOverride != null) {
        log.error { "Error: --no-config is mutually exclusive with -f / ZETA_CONFIG" }
        Tracer.emit()
        exitProcess(2)
    }
    val configPath: Path? = if (noConfig) null else try {
        resolveConfigFile(configOverride)
    } catch (e: ConfigFileMissingException) {
        log.error { "Error: ${e.message}" }
        Tracer.emit()
        exitProcess(2)
    }

    // Both the Zeta SDK and the CLI's own clients run on Ktor's OkHttp engine. OkHttp's default
    // Dispatcher uses non-daemon worker threads with a 60-second keepAlive — none of those
    // OkHttpClient instances get closed today (the SDK's `ZetaSdkClient.close()` is a TODO,
    // and the CLI's lazy `cliConfig.httpClient` has no close hook either). So the JVM waits
    // ~60s for those threads to time out before it can exit.
    //
    // Clikt's `.main(args)` already calls `exitProcess` for its own error types (UsageError,
    // PrintHelpMessage, …) but rethrows everything else. On the success path AND the
    // "uncaught throwable from a command" path we have to exit ourselves — otherwise the
    // process hangs for a minute after the failure.
    val exitCode = Tracer.root("cli.run", attrs = mapOf("argv" to args.joinToString(" "))) {
        try {
            ZetaCommand(configPath = configPath)
                .subcommands(
                    VersionCommand(),
                    DiscoverCommand(),
                    StatusCommand(),
                    RegisterCommand(),
                    AuthenticateCommand(),
                    LoginCommand(),
                    LogoutCommand(),
                    ForgetCommand(),
                    HttpCommand(),
                    WsCommand(),
                    ConnectorCommand().subcommands(
                        ConnectorInspectCommand(),
                        ConnectorConfigsCommand(),
                        ConnectorUseCommand(),
                        ConnectorGetCommand().subcommands(ConnectorGetCardsCommand()),
                    ),
                    PoppCommand().subcommands(PoppConnectorCommand(), PoppKartosCommand()),
                    stressCommand(),
                )
                .main(args)
            0
        } catch (e: Throwable) {
            // Renders the message + stacktrace through Logback (so `-v`, NO_COLOR, the
            // `zeta.stderr.pattern` formatting all apply) instead of the JVM's default uncaught
            // handler. Without this branch the throwable would print *and then* the process
            // would block on OkHttp's keepalive — much worse UX than an immediate exit 1.
            log.error(e) { "Command failed" }
            1
        }
    }
    Tracer.emit()
    exitProcess(exitCode)
}
