package de.gematik.zeta.cli

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import de.gematik.zeta.cli.client.HttpCommand
import de.gematik.zeta.cli.client.WsCommand
import de.gematik.zeta.cli.inspect.InspectCommand
import de.gematik.zeta.cli.connector.ConnectorCommand
import de.gematik.zeta.cli.connector.ConnectorConfigsCommand
import de.gematik.zeta.cli.connector.ConnectorGetCardsCommand
import de.gematik.zeta.cli.connector.ConnectorGetCommand
import de.gematik.zeta.cli.connector.ConnectorInspectCommand
import de.gematik.zeta.cli.popp.PoppCommand
import de.gematik.zeta.cli.popp.PoppConnectorCommand
import de.gematik.zeta.cli.status.StatusCommand
import de.gematik.zeta.cli.term.StderrColors
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Must run before *any* SLF4J access — Logback resolves `${zeta.stderr.pattern:-…}` at
    // config-load time, which is triggered by the first `LoggerFactory.getLogger` call. If
    // we let a top-level `val log = KotlinLogging.logger {}` initialize before this line,
    // Logback locks in the coloured fallback and later `setProperty` calls have no effect
    // (so `NO_COLOR=1` would not disable colours in redirected output).
    if (!StderrColors.enabled) {
        System.setProperty("zeta.stderr.pattern", "%-5level %logger{24} - %msg%n")
    }
    val log = KotlinLogging.logger("de.gematik.zeta.cli.Main")

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
    val exitCode = try {
        ZetaCommand()
            .subcommands(
                VersionCommand(),
                InspectCommand(),
                StatusCommand(),
                HttpCommand(),
                WsCommand(),
                ConnectorCommand().subcommands(
                    ConnectorInspectCommand(),
                    ConnectorConfigsCommand(),
                    ConnectorGetCommand().subcommands(ConnectorGetCardsCommand()),
                ),
                PoppCommand().subcommands(PoppConnectorCommand()),
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
    exitProcess(exitCode)
}
