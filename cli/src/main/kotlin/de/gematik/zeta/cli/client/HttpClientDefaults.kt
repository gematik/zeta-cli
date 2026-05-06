package de.gematik.zeta.cli.client

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
 * Only `-k/--insecure` flows through; `--ca-cert` does not, as the SDK's builder has no
 * extension point for custom trust managers.
 */
internal fun ZetaHttpClientBuilder.applyCliHttpDefaults(insecure: Boolean): ZetaHttpClientBuilder =
    disableServerValidation(insecure).logging(wireLogLevel, WireLogger)
