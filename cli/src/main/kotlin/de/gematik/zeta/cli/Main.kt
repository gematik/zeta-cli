package de.gematik.zeta.cli

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import de.gematik.zeta.cli.get.GetClientsCommand
import de.gematik.zeta.cli.get.GetCommand
import de.gematik.zeta.cli.inspect.InspectCommand
import de.gematik.zeta.cli.connector.ConnectorCommand
import de.gematik.zeta.cli.connector.ConnectorConfigsCommand
import de.gematik.zeta.cli.connector.ConnectorInspectCommand
import de.gematik.zeta.cli.register.RegisterCommand
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    ZetaCommand()
        .subcommands(
            VersionCommand(),
            InspectCommand(),
            RegisterCommand(),
            GetCommand().subcommands(GetClientsCommand()),
            ConnectorCommand().subcommands(ConnectorInspectCommand(), ConnectorConfigsCommand()),
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
