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
 *   - the curlie-style wire logger (via [SdkLogBridge]).
 *
 * **Not** plumbed (intentionally):
 *   - `--proxy` and friends. The SDK's own proxy-auth handling is broken: in
 *     `network/src/commonMain/.../ZetaHttpClient.kt::buildHttpClient` it interpolates
 *     the `CharArray` password into a string template, producing the JVM array
 *     `toString()` (`[C@…`) instead of the password content. Until the SDK fixes that,
 *     we don't hand it a `ProxyConfig`. The proxy still applies to the CLI's own clients
 *     via `HttpClientFactory.createHttpClient` (`zeta inspect`, `zeta connector inspect`,
 *     `zeta connector get cards`).
 *   - `--ca-cert` (SDK builder has no hook for a custom trust manager).
 */
internal fun ZetaHttpClientBuilder.applyCliHttpDefaults(cliConfig: CliConfig): ZetaHttpClientBuilder {
    // Idempotent: every applyCliHttpDefaults() call ensures the bridge is installed before
    // the SDK fires any logging. `Log.setLogger` is global, so re-installing is harmless.
    installSdkLogBridge()
    disableServerValidation(cliConfig.insecure)
    logging(wireLogLevel)
    return this
}
