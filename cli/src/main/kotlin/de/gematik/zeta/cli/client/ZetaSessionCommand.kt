package de.gematik.zeta.cli.client

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import de.gematik.connector.KonnektorClient
import de.gematik.connector.engine.okhttp.dotkonOkHttpClient
import de.gematik.connector.parseDotkon
import de.gematik.zeta.cli.ZetaCliktCommand
import de.gematik.zeta.cli.connector.ConnectorTokenProvider
import de.gematik.zeta.cli.connector.resolveKonFile
import de.gematik.zeta.cli.http.WireLogger
import de.gematik.zeta.cli.http.installCurlieLogging
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
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.authentication.smcb.SmcbTokenProvider
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.storage.StorageConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.readText

private val log = KotlinLogging.logger {}

/**
 * Connector-based authentication group: sign with the SMC-B via the Konnektor described
 * by `--connector-config`. Card identification is mutually-exclusive between the three
 * options, in stability order: Telematik-ID (most stable, persists across card replacements),
 * ICCSN (per-physical-card serial), card handle (Konnektor-session scoped).
 */
class ConnectorAuthOptions :
    OptionGroup(
        name = "Connector authentication",
        help = "Sign with the SMC-B via the Konnektor described by --connector-config.",
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
            "SMC-B card handle from the active Konnektor session — bound to the current " +
                "session, re-insertion changes it. Use --connector-telematik-id when possible. " +
                "(env: ZETA_CONNECTOR_CARD_HANDLE)",
    )
}

/**
 * PKCS#12 fallback authentication group: sign locally with a `.p12` keystore on disk.
 * For headless / no-Konnektor environments. All three fields are nullable individually;
 * [ZetaSessionCommand.validateAuthSelection] enforces all-or-none semantics.
 */
class P12AuthOptions :
    OptionGroup(
        name = "PKCS#12 fallback authentication",
        help =
            "Sign with a PKCS#12 keystore on disk. For headless / no-Konnektor environments. " +
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
     * with it, then clean up. Resources owned by the token provider (Konnektor HttpClient)
     * outlive the SDK calls because [SubjectTokenProvider.createSubjectToken] is invoked
     * lazily during the SDK's auth flow — they are closed in `finally`.
     */
    protected fun openSession(action: (ZetaSdkClient) -> Unit) {
        validateAuthSelection()

        val storagePath = zetaProfilePath(profile)
        log.info { "Persisting SDK state to $storagePath (profile: $profile)" }

        val (tokenProvider, cleanup) = buildTokenProvider()
        try {
            val sdk = buildSdk(storagePath, tokenProvider)
            action(sdk)
        } finally {
            cleanup()
        }
    }

    /**
     * Apply the CLI's shared HTTP options to a Zeta SDK [ZetaHttpClientBuilder]. Used at three
     * different builder sites — all of them run through here so wire logs land in Logback via
     * the curlie-style [WireLogger] (gated on `-vv`), never on stdout via Ktor's `Logger.DEFAULT`:
     *
     *   - [BuildConfig.httpClientBuilder] — the template the SDK uses for its internal HTTP
     *     calls (config discovery, registration, auth, ASL).
     *   - `sdk.httpClient { … }` — the REST client subcommands open.
     *   - `sdk.ws(builder = { … })` — the WebSocket client subcommands open.
     *
     * Currently only `-k/--insecure` flows through; `--ca-cert` does not, as the SDK's builder
     * has no extension point for custom trust managers.
     */
    protected fun ZetaHttpClientBuilder.applyCliHttpDefaults(): ZetaHttpClientBuilder =
        disableServerValidation(cliConfig.insecure)
            .logging(wireLogLevel, WireLogger)

    private fun buildSdk(storagePath: Path, tokenProvider: SubjectTokenProvider): ZetaSdkClient =
        ZetaSdk.build(
            POPP_RESOURCE,
            BuildConfig(
                productId = "zeta-cli",
                productVersion = "0.2.0",
                clientName = "zeta-cli",
                storageConfig = StorageConfig.Custom(JsonFileStorage(storagePath)),
                tpmConfig = object : TpmConfig {},
                authConfig =
                    AuthConfig(
                        scopes = listOf("popp"),
                        exp = 30,
                        aslProdEnvironment = false,
                        subjectTokenProvider = tokenProvider,
                        attestation = AttestationConfig.software(),
                        // SMC-B "Betriebsstätte Arzt" — must be present in the cert chain
                        // or the ASL handshake will refuse to complete.
                        requiredRoleOid = POPP_ROLE_OID,
                    ),
                platformProductId =
                    PlatformProductId.AppleProductId(
                        PlatformProductId.PLATFORM_APPLE,
                        "macos",
                        listOf(),
                    ),
                httpClientBuilder = ZetaHttpClientBuilder().applyCliHttpDefaults(),
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
                    "  • Konnektor (preferred): pass --connector-telematik-id, " +
                    "--connector-card-iccsn, or --connector-card-handle\n" +
                    "  • PKCS#12 fallback:      pass --p12-file, --p12-alias, and --p12-password",
            )
        }
        if (connectorOpts.isNotEmpty()) validateConnectorIdentifier(connectorOpts)
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

    private fun validateConnectorIdentifier(active: List<String>) {
        if (active.size > 1) {
            throw UsageError(
                "Pick one Konnektor card identifier — got ${active.size}: ${active.joinToString(", ")}.",
            )
        }
    }

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

    private fun buildTokenProvider(): Pair<SubjectTokenProvider, () -> Unit> {
        if (connectorActiveOptions().isNotEmpty()) {
            return buildConnectorTokenProvider(connectorAuth)
        }
        if (p12ActiveOptions().isNotEmpty()) {
            return buildP12TokenProvider(p12Auth) to { /* nothing to close */ }
        }
        // validateAuthSelection ran first, so this branch is unreachable.
        error("unreachable: no auth group active after validation")
    }

    private fun buildConnectorTokenProvider(auth: ConnectorAuthOptions): Pair<SubjectTokenProvider, () -> Unit> {
        val konPath =
            try {
                resolveKonFile(cliConfig.connectorConfig)
            } catch (e: Exception) {
                throw UsageError(e.message ?: "could not resolve connector config")
            }
        log.info { "Reading .kon from $konPath" }
        val dotkon = parseDotkon(konPath.readText())
        log.info { "Konnektor: ${dotkon.url}" }

        val httpClient =
            dotkonOkHttpClient(dotkon) {
                install(HttpTimeout) {
                    connectTimeoutMillis = cliConfig.connectTimeout.inWholeMilliseconds
                    requestTimeoutMillis = cliConfig.requestTimeout.inWholeMilliseconds
                }
                installCurlieLogging()
            }
        val konnektor =
            try {
                runBlocking { KonnektorClient.connect(httpClient, dotkon) }
            } catch (e: Throwable) {
                httpClient.close()
                throw e
            }

        val cardHandle =
            try {
                resolveCardHandle(konnektor, auth)
            } catch (e: Throwable) {
                httpClient.close()
                throw e
            }
        log.info { "Using SMC-B card handle: $cardHandle" }

        val provider =
            SmcbTokenProvider(
                SmcbTokenProvider.ConnectorConfig(
                    baseUrl = dotkon.url,
                    mandantId = dotkon.mandantId,
                    clientSystemId = dotkon.clientSystemId,
                    workspaceId = dotkon.workplaceId,
                    userId = dotkon.userId.orEmpty(),
                    cardHandle = cardHandle,
                ),
                connectorApi = ConnectorTokenProvider(konnektor),
            )
        return provider to { httpClient.close() }
    }

    /**
     * Resolve a card handle from the active connector identifier. validateAuthSelection
     * has already enforced that exactly one is set, so this is a deterministic dispatch.
     *
     * For `--connector-card-handle` we issue zero Konnektor requests. For ICCSN /
     * Telematik-ID we enumerate SMC-Bs via [KonnektorClient.listSmcbCards] **exactly
     * once** (which includes one ReadCardCertificate per card) and reuse the resulting
     * list for both the lookup and — on miss — the error listing. The previous version
     * hit the Konnektor twice on miss.
     */
    private fun resolveCardHandle(
        konnektor: KonnektorClient,
        auth: ConnectorAuthOptions,
    ): String {
        // Fast path: explicit handle, no Konnektor traffic.
        auth.cardHandle?.let { return it }

        val cards = runBlocking { konnektor.listSmcbCards() }
        val match: KonnektorClient.SmcbCard? =
            when {
                auth.iccsn != null -> {
                    cards.firstOrNull { it.iccsn == auth.iccsn }
                }

                auth.telematikId != null -> {
                    val tidMatches = cards.filter { it.telematikId == auth.telematikId }
                    if (tidMatches.size > 1) {
                        log.info {
                            "Multiple SMC-Bs with Telematik-ID '${auth.telematikId}' (${tidMatches.size}); " +
                                "picking the newest cert"
                        }
                    }
                    // Newest-cert-wins on a Telematik-ID tie (typical card-renewal window).
                    tidMatches.maxByOrNull { it.certificate?.notBefore?.time ?: Long.MIN_VALUE }
                }

                else -> {
                    error("unreachable: validateConnectorIdentifier ensured one is set")
                }
            }

        return match?.cardHandle ?: throw UsageError(buildAvailabilityListing(auth, cards))
    }

    private fun buildAvailabilityListing(
        auth: ConnectorAuthOptions,
        cards: List<KonnektorClient.SmcbCard>,
    ): String =
        buildString {
            val criterion =
                when {
                    auth.iccsn != null -> "ICCSN '${auth.iccsn}'"
                    auth.telematikId != null -> "Telematik-ID '${auth.telematikId}'"
                    else -> "the supplied identifier"
                }
            append("no SMC-B matches $criterion")
            if (cards.isEmpty()) {
                append(" (no SMC-Bs visible to the Konnektor)")
            } else {
                appendLine()
                append("available SMC-Bs:")
                cards.forEach { c ->
                    appendLine()
                    append(
                        "  ${c.ctId} / slot ${c.slotId}   handle=${c.cardHandle}   " +
                            "iccsn=${c.iccsn ?: "<unknown>"}   tid=${c.telematikId ?: "<unknown>"}",
                    )
                }
            }
        }

    private fun buildP12TokenProvider(auth: P12AuthOptions): SubjectTokenProvider {
        // validateP12Complete ensured all three are non-null before we got here.
        log.info { "Using p12 fallback: file=${auth.file} alias=${auth.alias}" }
        return SmbTokenProvider(
            SmbTokenProvider.Credentials(
                keystoreFile = auth.file!!.toString(),
                alias = auth.alias!!,
                password = auth.password!!,
            ),
        )
    }

    protected companion object {
        // popp dev service hard-coded for now; will become CLI options once these commands
        // need to target other Zeta-Guard resources.
        const val POPP_RESOURCE = "https://popp.dev.poppservice.de/"
        // SMC-B profession OID for "Betriebsstätte Arzt".
        const val POPP_ROLE_OID = "1.2.276.0.76.4.50"
    }
}
