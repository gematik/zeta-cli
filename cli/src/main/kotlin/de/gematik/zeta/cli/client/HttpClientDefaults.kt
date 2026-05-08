package de.gematik.zeta.cli.client

import de.gematik.zeta.cli.CliConfig
import de.gematik.zeta.cli.http.WireLogger
import de.gematik.zeta.cli.http.wireLogLevel
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder

/**
 * Apply the CLI's shared HTTP options to a Zeta SDK [ZetaHttpClientBuilder]. Used at every
 * builder site so wire logs land in Logback via the curlie-style [WireLogger] (gated on
 * `-vv`), never on stdout via Ktor's `Logger.DEFAULT`:
 *
 *   - [de.gematik.zeta.sdk.BuildConfig.httpClientBuilder] — template for the SDK's internal
 *     HTTP calls (config discovery, registration, auth, ASL).
 *   - `sdk.httpClient { … }` — the REST client subcommands open.
 *   - `sdk.ws(builder = { … })` — the WebSocket client subcommands open.
 *
 * Top-level extension so subcommand classes in any package can pull it in via the lambda
 * receiver chain without tripping on member-extension visibility from `ZetaSessionCommand`.
 *
 * Plumbed through:
 *   - `-k/--insecure` → disable server validation;
 *   - the curlie-style wire logger.
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
    disableServerValidation(cliConfig.insecure)
    logging(wireLogLevel, WireLogger)
    return this
}
