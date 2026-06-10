package de.gematik.zeta.cli

import de.gematik.zeta.cli.http.createHttpClient
import de.gematik.zeta.cli.output.OutputFormat
import de.gematik.zeta.sdk.network.http.client.config.ProxyConfig
import io.ktor.client.HttpClient
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class CliConfig {
    var verbose: Int = 0
    var insecure: Boolean = false
    var aslProdEnvironment: Boolean = false
    var caCertFiles: List<Path> = emptyList()
    var connectTimeout: Duration = 5.seconds
    var requestTimeout: Duration = 30.seconds
    var outputFormat: OutputFormat = OutputFormat.TEXT
    /**
     * Explicit `.kon` selector from `-c/--connector-config` or `ZETA_CONNECTOR_CONFIG`. `null`
     * means nothing was specified — callers fall back to the `active` file, then `"default"`
     * via `selectConnectorConfig()`. Resolution rules in `DotkonPaths.kt`.
     */
    var connectorConfig: String? = null

    /**
     * HTTP/SOCKS proxy applied uniformly to every outbound HTTP/WebSocket connection — the
     * CLI's own Ktor clients (`inspect`, `connector inspect`, `get clients`) and every
     * `ZetaHttpClientBuilder` the SDK gets handed (token endpoint, ASL, app HTTP, app WS).
     */
    var proxy: ProxyConfig? = null

    val httpClient: HttpClient by lazy {
        createHttpClient(
            connectTimeout = connectTimeout,
            requestTimeout = requestTimeout,
            insecure = insecure,
            caCertFiles = caCertFiles,
            proxy = proxy,
        )
    }
}
