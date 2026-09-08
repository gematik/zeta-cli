package de.gematik.zeta.cli.client

import de.gematik.zeta.cli.CliConfig
import de.gematik.zeta.cli.http.SdkLogBridge
import de.gematik.zeta.cli.http.installSdkLogBridge
import de.gematik.zeta.cli.http.wireLogLevel
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.hours

/**
 * Upper bound on how long a validated OCSP/CRL response is reused. The SDK defaults to an hour,
 * which costs a fresh responder round-trip on nearly every invocation of a CLI that runs for a
 * second at a time. The cache is still capped by the response's own `nextUpdate`, so this only
 * extends reuse for responses that would outlive it.
 */
private val REVOCATION_CACHE = 24.hours

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
 *   - `--ca-cert FILE` (repeatable) → forward each PEM to `ZetaHttpClientBuilder.addCaPem(...)` so the
 *     SDK's own discovery/registration/auth/ASL calls trust a private/internal CA, not just the CLI's
 *     `zeta http`/`ws` client;
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
 */
internal fun ZetaHttpClientBuilder.applyCliHttpDefaults(cliConfig: CliConfig): ZetaHttpClientBuilder {
    // Idempotent: every applyCliHttpDefaults() call ensures the bridge is installed before
    // the SDK fires any logging. `Log.setLogger` is global, so re-installing is harmless.
    installSdkLogBridge()
    disableServerValidation(cliConfig.insecure)
    // addCaPem parses a whole PEM blob (root + intermediates in one --ca-cert file all count).
    cliConfig.caCertFiles.forEach { addCaPem(it.readText()) }
    timeouts(
        connectMs = cliConfig.connectTimeout.inWholeMilliseconds,
        requestMs = cliConfig.requestTimeout.inWholeMilliseconds,
    )
    revocationCacheDuration(REVOCATION_CACHE.inWholeSeconds)
    logging(wireLogLevel)
    cliConfig.proxy?.let { proxy(it) }
    return this
}
