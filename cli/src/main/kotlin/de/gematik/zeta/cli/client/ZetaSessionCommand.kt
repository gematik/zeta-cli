package de.gematik.zeta.cli.client

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import de.gematik.zeta.cli.ZetaCliktCommand
import de.gematik.zeta.cli.connector.ConnectorSession
import de.gematik.zeta.cli.connector.openConnectorSession
import de.gematik.zeta.cli.http.WireLogger
import de.gematik.zeta.cli.http.wireLogLevel
import de.gematik.zeta.cli.storage.JsonFileStorage
import de.gematik.zeta.cli.storage.zetaProfilePath
import de.gematik.zeta.sdk.BuildConfig
import de.gematik.zeta.sdk.TpmConfig
import de.gematik.zeta.sdk.ZetaSdk
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.attestation.model.AttestationConfig
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.SubjectTokenProvider
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.storage.StorageConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path

private val log = KotlinLogging.logger {}

/**
 * Connector-based authentication group: sign with the SMC-B via the Connector described
 * by `--connector-config`. Card identification is mutually-exclusive between the three
 * options, in stability order: Telematik-ID (most stable, persists across card replacements),
 * ICCSN (per-physical-card serial), card handle (Connector-session scoped).
 */
class ConnectorAuthOptions :
    OptionGroup(
        name = "Connector authentication",
        help = "Sign with the SMC-B via the Connector described by --connector-config.",
    ) {
    val telematikId: String? by option(
        "--connector-telematik-id",
        metavar = "TID",
        envvar = "ZETA_CONNECTOR_TELEMATIK_ID",
        help =
            "SMC-B Telematik-ID (preferred — stable across card replacements; the " +
                "newest cert wins on tie). (env: ZETA_CONNECTOR_TELEMATIK_ID)",
    )
    val iccsn: String? by option(
        "--connector-card-iccsn",
        metavar = "ICCSN",
        envvar = "ZETA_CONNECTOR_CARD_ICCSN",
        help =
            "SMC-B ICCSN (the chip-card serial printed on the card body). Stable for " +
                "the physical card but changes when the card is reissued. " +
                "(env: ZETA_CONNECTOR_CARD_ICCSN)",
    )
    val cardHandle: String? by option(
        "--connector-card-handle",
        metavar = "HANDLE",
        envvar = "ZETA_CONNECTOR_CARD_HANDLE",
        help =
            "SMC-B card handle from the active Connector session — bound to the current " +
                "session, re-insertion changes it. Use --connector-telematik-id when possible. " +
                "(env: ZETA_CONNECTOR_CARD_HANDLE)",
    )
}

/**
 * PKCS#12 fallback authentication group: sign locally with a `.p12` keystore on disk.
 * For headless / no-Connector environments. All three fields are nullable individually;
 * [ZetaSessionCommand.validateAuthSelection] enforces all-or-none semantics.
 */
class P12AuthOptions :
    OptionGroup(
        name = "PKCS#12 fallback authentication",
        help =
            "Sign with a PKCS#12 keystore on disk. For headless / no-Connector environments. " +
                "All three options are required together.",
    ) {
    val file: Path? by option(
        "--p12-file",
        metavar = "FILE",
        help = "PKCS#12 keystore file.",
    ).path(canBeFile = true, canBeDir = false)

    val alias: String? by option(
        "--p12-alias",
        metavar = "NAME",
        help = "Alias inside the PKCS#12 keystore.",
    )

    val password: String? by option(
        "--p12-password",
        metavar = "PASSWORD",
        envvar = "ZETA_P12_PASSWORD",
        help = "Password for the PKCS#12 keystore. (env: ZETA_P12_PASSWORD)",
    )
}

/**
 * Base for any subcommand that needs an authenticated [ZetaSdkClient]. Owns the shared
 * `--profile` option, the two auth-option groups, and the SDK construction. Subclasses
 * override `runCommand()` and call [openSession] to receive a configured SDK.
 *
 * Token-provider construction (Connector or PKCS#12) lives in [TokenProviders.kt] and the
 * SMC-B card-handle resolution in [CardResolver.kt] — this class only orchestrates them.
 *
 * The popp resource and role-OID are hard-coded for now — generalising to other Zeta-Guard
 * services means lifting them into options on this base, not on each subcommand.
 */
abstract class ZetaSessionCommand(name: String) : ZetaCliktCommand(name = name) {
    private val profile: String by option(
        "--profile",
        metavar = "NAME",
        envvar = "ZETA_PROFILE",
        help =
            "Storage profile name. SDK state (registration, tokens, …) is persisted to " +
                "\$XDG_CONFIG_HOME/telematik/zeta/<profile>.storage.json. (env: ZETA_PROFILE)",
    ).default("default")

    // Both groups are plain (non-cooccurring) [OptionGroup]s and always present.
    //
    // Why not `cooccurring()`: Clikt's CoOccurringOptionGroup demands at least one
    // `.required()` member. The connector group has none (its three fields are
    // alternatives, not co-required); and Clikt's check is enforced eagerly at command
    // construction, surfacing as a stack trace on `--help` rather than a clean usage
    // error. Manual validation in [validateAuthSelection] is cleaner.
    private val connectorAuth by ConnectorAuthOptions()
    private val p12Auth by P12AuthOptions()

    /**
     * Validate auth selection, build a token provider, build a Zeta SDK client, run [action]
     * with it, then clean up. The second [action] argument is the Connector session backing
     * the token provider, or `null` when the user picked the PKCS#12 path — popp-style
     * subcommands that need to call back into the Connector (e.g. `SecureSendAPDU`) reuse
     * the same session here rather than opening a second one.
     *
     * The Connector session outlives the SDK calls because
     * [SubjectTokenProvider.createSubjectToken] is invoked lazily during the SDK's auth
     * flow — closing it eagerly would crash the flow mid-way. It's closed in `finally`.
     */
    // `internal` rather than `protected` because [ConnectorSession] is `internal`; Kotlin
    // would otherwise complain about a stricter member exposing a looser parameter type.
    // All concrete subcommands live in the same module so this still works.
    /**
     * @param resource the OAuth resource indicator (RFC 8707) — typically the
     *   `scheme://host[:port]/` origin of the URL the subcommand is calling. Use [originOf]
     *   to derive it from the request URL.
     * @param scopes OAuth scopes to request. The Zeta-Guard auth server issues an access
     *   token granting exactly these. Each subcommand decides whether to expose this as a
     *   user option (`zeta http` / `zeta ws`) or to hardcode it (`zeta popp connector`
     *   always asks for `popp`).
     */
    internal fun openSession(
        resource: String,
        scopes: List<String>,
        action: (ZetaSdkClient, ConnectorSession?) -> Unit,
    ) {
        validateAuthSelection()

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

    private fun buildSdk(
        resource: String,
        scopes: List<String>,
        storagePath: Path,
        tokenProvider: SubjectTokenProvider,
    ): ZetaSdkClient =
        ZetaSdk.build(
            resource,
            BuildConfig(
                productId = "zeta-cli",
                productVersion = "0.2.0",
                clientName = "zeta-cli",
                storageConfig = StorageConfig.Custom(JsonFileStorage(storagePath)),
                tpmConfig = object : TpmConfig {},
                authConfig =
                    AuthConfig(
                        scopes = scopes,
                        exp = 30,
                        aslProdEnvironment = false,
                        subjectTokenProvider = tokenProvider,
                        attestation = AttestationConfig.software(),
                        // SMC-B "Betriebsstätte Arzt" — must be present in the cert chain
                        // or the ASL handshake will refuse to complete.
                        requiredRoleOid = DEFAULT_ROLE_OID,
                    ),
                platformProductId =
                    PlatformProductId.AppleProductId(
                        PlatformProductId.PLATFORM_APPLE,
                        "macos",
                        listOf(),
                    ),
                httpClientBuilder = ZetaHttpClientBuilder().applyCliHttpDefaults(insecure = cliConfig.insecure),
            ),
        ).also { log.debug { "Created Zeta SDK client" } }

    /**
     * Enforce mutual exclusion + completeness. The two groups are mutually exclusive;
     * within the connector group, exactly one of three identifiers; within the p12
     * group, all three fields together or none.
     *
     * Every error path throws a [UsageError] so Clikt prints a one-line usage message
     * (no stack trace).
     */
    private fun validateAuthSelection() {
        val connectorOpts = connectorActiveOptions()
        val p12Opts = p12ActiveOptions()

        if (connectorOpts.isNotEmpty() && p12Opts.isNotEmpty()) {
            throw UsageError(
                "Connector and PKCS#12 authentication cannot be combined.\n" +
                    "  connector: ${connectorOpts.joinToString(", ")}\n" +
                    "  pkcs12:    ${p12Opts.joinToString(", ")}",
            )
        }
        if (connectorOpts.isEmpty() && p12Opts.isEmpty()) {
            throw UsageError(
                "Choose how to authenticate:\n" +
                    "  • Connector (preferred): pass --connector-telematik-id, " +
                    "--connector-card-iccsn, or --connector-card-handle\n" +
                    "  • PKCS#12 fallback:      pass --p12-file, --p12-alias, and --p12-password",
            )
        }
        if (connectorOpts.size > 1) {
            throw UsageError(
                "Pick one Connector card identifier — got ${connectorOpts.size}: ${connectorOpts.joinToString(", ")}.",
            )
        }
        if (p12Opts.isNotEmpty()) validateP12Complete()
    }

    /** Names of every connector identifier the user actually set, in declaration order. */
    private fun connectorActiveOptions(): List<String> =
        listOfNotNull(
            connectorAuth.telematikId?.let { "--connector-telematik-id" },
            connectorAuth.iccsn?.let { "--connector-card-iccsn" },
            connectorAuth.cardHandle?.let { "--connector-card-handle" },
        )

    /** Names of every PKCS#12 option the user actually set, in declaration order. */
    private fun p12ActiveOptions(): List<String> =
        listOfNotNull(
            p12Auth.file?.let { "--p12-file" },
            p12Auth.alias?.let { "--p12-alias" },
            p12Auth.password?.let { "--p12-password" },
        )

    private fun validateP12Complete() {
        val missing =
            listOfNotNull(
                "--p12-file".takeIf { p12Auth.file == null },
                "--p12-alias".takeIf { p12Auth.alias == null },
                "--p12-password".takeIf { p12Auth.password == null },
            )
        if (missing.isNotEmpty()) {
            throw UsageError(
                "PKCS#12 authentication requires all three options. Missing: ${missing.joinToString(", ")}.",
            )
        }
    }

    private fun buildTokenProvider(): Pair<SubjectTokenProvider, ConnectorSession?> {
        if (connectorActiveOptions().isNotEmpty()) {
            // .kon parsing + HttpClient construction (cheap); SDS load + SMC-B enumeration
            // are deferred to the first SDK `createSubjectToken` call via [LazySubjectTokenProvider].
            val session = openConnectorSession(
                connectorConfigName = cliConfig.connectorConfig,
                connectTimeout = cliConfig.connectTimeout,
                requestTimeout = cliConfig.requestTimeout,
            )
            val provider = buildConnectorTokenProvider(
                session = session,
                cardHandle = connectorAuth.cardHandle,
                iccsn = connectorAuth.iccsn,
                telematikId = connectorAuth.telematikId,
            )
            return provider to session
        }
        if (p12ActiveOptions().isNotEmpty()) {
            // validateP12Complete ensured all three are non-null before we got here.
            return buildP12TokenProvider(
                file = p12Auth.file!!,
                alias = p12Auth.alias!!,
                password = p12Auth.password!!,
            ) to null
        }
        // validateAuthSelection ran first, so this branch is unreachable.
        error("unreachable: no auth group active after validation")
    }

    protected companion object {
        // SMC-B profession OID for "Betriebsstätte Arzt". The Zeta-Guard ASL handshake
        // requires the server's TI cert to advertise this — the popp dev service does, and
        // observed Zeta-Guard PEPs use the same OID. Will become a CLI option if a service
        // ever needs a different one.
        const val DEFAULT_ROLE_OID = "1.2.276.0.76.4.50"
    }
}
