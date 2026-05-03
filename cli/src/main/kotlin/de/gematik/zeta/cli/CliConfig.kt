package de.gematik.zeta.cli

import de.gematik.zeta.cli.http.createHttpClient
import de.gematik.zeta.cli.output.OutputFormat
import io.ktor.client.HttpClient
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class CliConfig {
    var verbose: Int = 0
    var insecure: Boolean = false
    var caCertFiles: List<Path> = emptyList()
    var connectTimeout: Duration = 5.seconds
    var requestTimeout: Duration = 30.seconds
    var outputFormat: OutputFormat = OutputFormat.TEXT
    /** `.kon` file selector for the `connector` subcommand tree. Resolution rules in `DotkonPaths.kt`. */
    var connectorConfig: String = "default"

    val httpClient: HttpClient by lazy {
        createHttpClient(
            connectTimeout = connectTimeout,
            requestTimeout = requestTimeout,
            insecure = insecure,
            caCertFiles = caCertFiles,
        )
    }
}
