package de.gematik.zeta.cli.client

import de.gematik.zeta.cli.CliConfig
import de.gematik.zeta.cli.http.SdkLogBridge
import de.gematik.zeta.cli.http.installSdkLogBridge
import de.gematik.zeta.cli.http.wireLogLevel
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder

/**
 * Apply the CLI's shared HTTP options to a Zeta SDK [ZetaHttpClientBuilder]. Used at every
 * builder site so:
 *
 *  - [de.gematik.zeta.sdk.BuildConfig.httpClientBuilder] — template for the SDK's internal
 *    HTTP calls (config discovery, registration, auth, ASL).
 *  - `sdk.httpClient { … }` — the REST client subcommands open.
 *  - `sdk.ws(builder = { … })` — the WebSocket client subcommands open.
 *
 * Wire logging is enabled at `-vv` via the SDK's public single-arg `logging(level)` overload.
 * The Logger arg is now `internal` in the SDK, so the routing to Logback happens one layer
 * down: [SdkLogBridge] installs as the SDK's `ZetaLogger`, intercepts the wire dumps that
 * `MonitoringConfig`'s default Logger funnels through `Log.i { message }`, and forwards
 * them to the `de.gematik.zeta.http.wire` SLF4J logger — same curlie-style output as before.
 *
 * Plumbed through:
 *   - `-k/--insecure` → disable server validation;
 *   - `--connect-timeout`/`--request-timeout` → forward to `ZetaHttpClientBuilder.timeouts(...)`;
 *   - `--proxy` (and `--proxy-user`/`--proxy-password`) → forward to `ZetaHttpClientBuilder.proxy(...)`;
 *   - the curlie-style wire logger (via [SdkLogBridge]).
 *
 * SDK proxy caveats (as of `latest`):
 *   - The earlier `CharArray.toString()` interpolation bug is fixed (the SDK now calls
 *     `password.concatToString()` when forwarding SOCKS credentials).
 *   - **SOCKS auth** works, but via *global* JVM system properties (`java.net.socks.username` /
 *     `java.net.socks.password`) — last-writer-wins across SDK instances in the same process.
 *   - **HTTP-proxy auth** is not wired: `ZetaHttpClient.jvm.kt::applyProxy` does not set
 *     OkHttp's `proxyAuthenticator`. Unauthenticated HTTP proxies and SOCKS work; HTTP
 *     proxies that require credentials will get a 407 from the SDK's internal calls.
 *
 * **Not** plumbed (intentionally):
 *   - `--ca-cert` (SDK builder has no hook for a custom trust manager).
 */
internal fun ZetaHttpClientBuilder.applyCliHttpDefaults(cliConfig: CliConfig): ZetaHttpClientBuilder {
    // Idempotent: every applyCliHttpDefaults() call ensures the bridge is installed before
    // the SDK fires any logging. `Log.setLogger` is global, so re-installing is harmless.
    installSdkLogBridge()
    disableServerValidation(cliConfig.insecure)
    timeouts(
        connectMs = cliConfig.connectTimeout.inWholeMilliseconds,
        requestMs = cliConfig.requestTimeout.inWholeMilliseconds,
    )
    logging(wireLogLevel)
    cliConfig.proxy?.let { proxy(it) }
    return this
}
