package de.gematik.zeta.cli.client

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.groupChoice
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required as requiredOpt
import com.github.ajalt.clikt.parameters.types.path
import de.gematik.zeta.cli.ZetaProfileCommand
import de.gematik.zeta.cli.connector.ConnectorSession
import de.gematik.zeta.cli.connector.openConnectorSession
import de.gematik.zeta.cli.sdk.buildZetaSdkClient
import de.gematik.zeta.cli.storage.zetaProfilePath
import de.gematik.zeta.cli.trace.Tracer
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.authentication.SubjectTokenProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path

private val log = KotlinLogging.logger {}

/**
 * One of the three SMC-B card identifiers accepted by Connector-backed auth. Stability
 * order (best → worst): Telematik-ID (persists across card replacements),
 * ICCSN (per-physical-card serial), card handle (Connector-session scoped).
 */
internal sealed class SmcbCardId {
    abstract val value: String
    data class TelematikId(override val value: String) : SmcbCardId()
    data class Iccsn(override val value: String) : SmcbCardId()
    data class CardHandle(override val value: String) : SmcbCardId()
}

/**
 * Common base for the two auth-method groups. Sealed so `--auth-method`'s [groupChoice]
 * resolves to a closed set and downstream `when` is exhaustive.
 */
internal sealed class AuthMethodOptions(name: String, help: String) : OptionGroup(name, help)

/**
 * Connector-backed authentication: sign with the SMC-B via the Konnektor described by
 * `--connector-config`. The three card identifiers are mutually exclusive; [cardId]
 * resolves them to exactly one [SmcbCardId] and throws a [UsageError] otherwise.
 *
 * Clikt's `mutuallyExclusiveOptions` would normally enforce this declaratively, but its
 * delegate only accepts a `BaseCliktCommand` receiver — it can't live inside an
 * [OptionGroup]. So we keep three plain options and validate on access. This getter only
 * runs when `--auth-method connector` activates the group, so a `--auth-method p12`
 * invocation never trips these checks.
 */
internal class ConnectorAuthOptions : AuthMethodOptions(
    name = "Zeta authentication — Connector method",
    help = "Sign with the SMC-B via the Connector described by --connector-config.",
) {
    private val telematikIdOpt: String? by option(
        "--auth-connector-telematik-id",
        metavar = "TID",
        envvar = "ZETA_AUTH_CONNECTOR_TELEMATIK_ID",
        help = "SMC-B Telematik-ID (preferred — stable across card replacements; the " +
            "newest cert wins on tie). (env: ZETA_AUTH_CONNECTOR_TELEMATIK_ID)",
    )
    private val iccsnOpt: String? by option(
        "--auth-connector-card-iccsn",
        metavar = "ICCSN",
        envvar = "ZETA_AUTH_CONNECTOR_CARD_ICCSN",
        help = "SMC-B ICCSN (the chip-card serial printed on the card body). Stable for " +
            "the physical card but changes when the card is reissued. " +
            "(env: ZETA_AUTH_CONNECTOR_CARD_ICCSN)",
    )
    private val cardHandleOpt: String? by option(
        "--auth-connector-card-handle",
        metavar = "HANDLE",
        envvar = "ZETA_AUTH_CONNECTOR_CARD_HANDLE",
        help = "SMC-B card handle from the active Connector session — bound to the current " +
            "session, re-insertion changes it. Use --auth-connector-telematik-id when possible. " +
            "(env: ZETA_AUTH_CONNECTOR_CARD_HANDLE)",
    )

    val cardId: SmcbCardId
        get() {
            val ids = listOfNotNull(
                telematikIdOpt?.let { "--auth-connector-telematik-id" to SmcbCardId.TelematikId(it) },
                iccsnOpt?.let { "--auth-connector-card-iccsn" to SmcbCardId.Iccsn(it) },
                cardHandleOpt?.let { "--auth-connector-card-handle" to SmcbCardId.CardHandle(it) },
            )
            return when (ids.size) {
                1 -> ids.single().second
                0 -> throw UsageError(
                    "--auth-method connector requires one of " +
                        "--auth-connector-telematik-id, --auth-connector-card-iccsn, " +
                        "or --auth-connector-card-handle.",
                )
                else -> throw UsageError(
                    "Pick one Connector card identifier — got ${ids.size}: " +
                        ids.joinToString(", ") { it.first } + ".",
                )
            }
        }
}

/**
 * PKCS#12 fallback authentication: sign locally with a `.p12` keystore on disk. For headless
 * / no-Connector environments. The keystore file is required; alias and password default to
 * the conventional gematik test values.
 */
internal class P12AuthOptions : AuthMethodOptions(
    name = "Zeta authentication — PKCS#12 method",
    help = "Sign with a PKCS#12 keystore on disk. For headless / no-Connector environments.",
) {
    val file: Path by option(
        "--auth-p12-file",
        metavar = "FILE",
        envvar = "ZETA_AUTH_P12_FILE",
        help = "PKCS#12 keystore file. (env: ZETA_AUTH_P12_FILE)",
    ).path(canBeFile = true, canBeDir = false).requiredOpt()

    val alias: String by option(
        "--auth-p12-alias",
        metavar = "NAME",
        envvar = "ZETA_AUTH_P12_ALIAS",
        help = "Alias inside the PKCS#12 keystore. Default: 'alias'. (env: ZETA_AUTH_P12_ALIAS)",
    ).default("alias")

    val password: String by option(
        "--auth-p12-password",
        metavar = "PASSWORD",
        envvar = "ZETA_AUTH_P12_PASSWORD",
        help = "Password for the PKCS#12 keystore. Default: '00'. (env: ZETA_AUTH_P12_PASSWORD)",
    ).default("00")
}

/**
 * Base for any subcommand that needs an authenticated [ZetaSdkClient]. Owns the shared
 * `--profile` option, the `--auth-method` switch + its dependent option group, and the SDK
 * construction. Subclasses override `runCommand()` and call [openSession] to receive a
 * configured SDK.
 *
 * Auth-method selection and per-method validation are entirely declarative: `--auth-method`
 * is a Clikt [groupChoice] that activates exactly one of [ConnectorAuthOptions] /
 * [P12AuthOptions], and each group's internal constraints (exactly-one card identifier;
 * mandatory keystore file) are enforced by Clikt's native option machinery. No cross-group
 * exclusion check is needed — Clikt rejects flags from the inactive group as unknown.
 *
 * Token-provider construction (Connector or PKCS#12) lives in [TokenProviders.kt] and the
 * SMC-B card-handle resolution in [CardResolver.kt] — this class only orchestrates them.
 *
 * The popp resource and role-OID are hard-coded for now — generalising to other Zeta-Guard
 * services means lifting them into options on this base, not on each subcommand.
 */
abstract class ZetaSessionCommand(
    name: String,
) : ZetaProfileCommand(name = name) {
    // `--profile` is inherited from [ZetaProfileCommand].

    private val auth: AuthMethodOptions by option(
        "--auth-method",
        metavar = "METHOD",
        envvar = "ZETA_AUTH_METHOD",
        help = "Authentication method: 'connector' (SMC-B via Konnektor — preferred) or " +
            "'p12' (PKCS#12 keystore — for headless environments). (env: ZETA_AUTH_METHOD)",
    ).groupChoice(
        "connector" to ConnectorAuthOptions(),
        "p12" to P12AuthOptions(),
    ).required()

    /**
     * Build a token provider, build a Zeta SDK client, run [action] with it, then clean up.
     * The second [action] argument is the Connector session backing the token provider, or
     * `null` when the user picked the PKCS#12 path — popp-style subcommands that need to
     * call back into the Connector (e.g. `SecureSendAPDU`) reuse the same session here
     * rather than opening a second one.
     *
     * The Connector session outlives the SDK calls because
     * [SubjectTokenProvider.createSubjectToken] is invoked lazily during the SDK's auth
     * flow — closing it eagerly would crash the flow mid-way. It's closed in `finally`.
     *
     * @param resource the OAuth resource indicator (RFC 8707) — typically the
     *   `scheme://host[:port]/` origin of the URL the subcommand is calling. Use [originOf]
     *   to derive it from the request URL.
     * @param scopes OAuth scopes to request. The Zeta-Guard auth server issues an access
     *   token granting exactly these. Each subcommand decides whether to expose this as a
     *   user option (`zeta http` / `zeta ws`) or to hardcode it (`zeta popp connector`
     *   always asks for `popp`).
     */
    // `internal` rather than `protected` because [ConnectorSession] is `internal`; Kotlin
    // would otherwise complain about a stricter member exposing a looser parameter type.
    // All concrete subcommands live in the same module so this still works.
    internal fun openSession(
        resource: String,
        scopes: List<String>,
        action: (ZetaSdkClient, ConnectorSession?) -> Unit,
    ) {
        Tracer.span(
            "sdk.session",
            attrs = mapOf("resource" to resource, "scopes" to scopes.joinToString(",")),
        ) {
            val storagePath = zetaProfilePath(profile)
            log.info { "Persisting SDK state to $storagePath (profile: $profile, resource: $resource, scopes: $scopes)" }

            val (tokenProvider, session) = buildTokenProvider()
            try {
                val sdk = buildSdk(resource, scopes, storagePath, tokenProvider)
                action(sdk, session)
            } finally {
                session?.close()
            }
        }
    }

    private fun buildSdk(
        resource: String,
        scopes: List<String>,
        storagePath: Path,
        tokenProvider: SubjectTokenProvider,
    ): ZetaSdkClient =
        buildZetaSdkClient(
            resource = resource,
            scopes = scopes,
            storagePath = storagePath,
            tokenProvider = tokenProvider,
            cliConfig = cliConfig,
        ).also { log.debug { "Created Zeta SDK client" } }

    private fun buildTokenProvider(): Pair<SubjectTokenProvider, ConnectorSession?> =
        when (val opts = auth) {
            is ConnectorAuthOptions -> {
                // .kon parsing + HttpClient construction (cheap); SDS load + SMC-B enumeration
                // are deferred to the first SDK `createSubjectToken` call via [LazySubjectTokenProvider].
                val session = openConnectorSession(
                    connectorConfigName = cliConfig.connectorConfig,
                    connectTimeout = cliConfig.connectTimeout,
                    requestTimeout = cliConfig.requestTimeout,
                    proxy = cliConfig.proxy,
                )
                val (handle, iccsn, tid) = when (val id = opts.cardId) {
                    is SmcbCardId.CardHandle -> Triple(id.value, null, null)
                    is SmcbCardId.Iccsn -> Triple(null, id.value, null)
                    is SmcbCardId.TelematikId -> Triple(null, null, id.value)
                }
                val provider = buildConnectorTokenProvider(
                    session = session,
                    cardHandle = handle,
                    iccsn = iccsn,
                    telematikId = tid,
                )
                provider to session
            }
            is P12AuthOptions -> buildP12TokenProvider(
                file = opts.file,
                alias = opts.alias,
                password = opts.password,
            ) to null
        }
}
