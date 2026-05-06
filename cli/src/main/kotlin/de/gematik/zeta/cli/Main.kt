package de.gematik.zeta.cli

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import de.gematik.zeta.cli.client.HttpCommand
import de.gematik.zeta.cli.client.WsCommand
import de.gematik.zeta.cli.get.GetClientsCommand
import de.gematik.zeta.cli.get.GetCommand
import de.gematik.zeta.cli.inspect.InspectCommand
import de.gematik.zeta.cli.connector.ConnectorCommand
import de.gematik.zeta.cli.connector.ConnectorConfigsCommand
import de.gematik.zeta.cli.connector.ConnectorGetCardsCommand
import de.gematik.zeta.cli.connector.ConnectorGetCommand
import de.gematik.zeta.cli.connector.ConnectorInspectCommand
import de.gematik.zeta.cli.popp.PoppCommand
import de.gematik.zeta.cli.popp.PoppConnectorCommand
import de.gematik.zeta.cli.term.StderrColors
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Resolved before the first SLF4J call so Logback picks up the right encoder pattern.
    // logback.xml falls back to a coloured pattern when this property is unset.
    if (!StderrColors.enabled) {
        System.setProperty("zeta.stderr.pattern", "%-5level %logger{24} - %msg%n")
    }

    ZetaCommand()
        .subcommands(
            VersionCommand(),
            InspectCommand(),
            HttpCommand(),
            WsCommand(),
            GetCommand().subcommands(GetClientsCommand()),
            ConnectorCommand().subcommands(
                ConnectorInspectCommand(),
                ConnectorConfigsCommand(),
                ConnectorGetCommand().subcommands(ConnectorGetCardsCommand()),
            ),
            PoppCommand().subcommands(PoppConnectorCommand()),
        )
        .main(args)

    // The Zeta SDK uses Ktor's OkHttp engine on JVM (network-jvm/ZetaHttpClient.jvm.kt) with
    // OkHttp's default Dispatcher — its ThreadFactory marks worker threads as NON-daemon and
    // their keepAliveTime is 60s. Each ZetaHttpClientBuilder.build() inside the SDK (config,
    // registration, auth, ASL handlers) spawns its own preconfigured OkHttpClient + Dispatcher;
    // none of them get shut down because ZetaSdkClient.close() is a TODO upstream. Result: the
    // JVM waits ~60s for those Dispatcher threads to time out before it can exit. exitProcess
    // bypasses that wait. Clikt already calls exitProcess on errors, so this only fires on the
    // success path.
    exitProcess(0)
}
