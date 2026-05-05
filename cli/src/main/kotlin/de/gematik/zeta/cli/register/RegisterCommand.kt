package de.gematik.zeta.cli.register

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import de.gematik.connector.ConnectorException
import de.gematik.connector.KonnektorClient
import de.gematik.connector.engine.okhttp.dotkonOkHttpClient
import de.gematik.connector.parseDotkon
import de.gematik.zeta.cli.ZetaCliktCommand
import de.gematik.zeta.cli.connector.ConnectorTokenProvider
import de.gematik.zeta.cli.connector.resolveKonFile
import de.gematik.zeta.cli.http.installCurlieLogging
import de.gematik.zeta.cli.storage.JsonFileStorage
import de.gematik.zeta.cli.storage.zetaProfilePath
import de.gematik.zeta.sdk.BuildConfig
import de.gematik.zeta.sdk.StorageConfig
import de.gematik.zeta.sdk.TpmConfig
import de.gematik.zeta.sdk.ZetaSdk
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.attestation.model.AttestationConfig
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.SubjectTokenProvider
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.authentication.smcb.SmcbTokenProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
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
 * the `register` command's [validateAuthSelection] enforces all-or-none semantics.
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

class RegisterCommand : ZetaCliktCommand(name = "register") {
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

    override fun help(context: Context) = "Register a client with Zeta Guard."

    override fun runCommand() {
        validateAuthSelection()

        val storagePath = zetaProfilePath(profile)
        log.info { "Persisting SDK state to $storagePath (profile: $profile)" }

        // Build the subject-token provider first; resources it owns (Konnektor HttpClient)
        // must outlive sdk.register() because createSubjectToken is called lazily during
        // the SDK's auth flow. We close them in `finally`.
        val (tokenProvider, cleanup) = buildTokenProvider()
        try {
            runRegistration(storagePath, tokenProvider)
        } finally {
            cleanup()
        }
    }

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

    private fun runRegistration(
        storagePath: Path,
        tokenProvider: SubjectTokenProvider,
    ) {
        val resource = "https://popp.dev.poppservice.de/"
        val sdk =
            ZetaSdk.build(
                resource,
                BuildConfig(
                    "zeta-cli",
                    "0.2.0",
                    "zeta-cli",
                    // aesB64Key is unused on the Custom-provider path in zeta-sdk 0.5.1
                    // (`cfg.storageConfig.provider ?: provideSdkStorage(aesB64Key)`); the
                    // constructor still demands a valid Base64-AES-256 placeholder.
                    StorageConfig(
                        JsonFileStorage(storagePath),
                        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                    ),
                    object : TpmConfig {},
                    AuthConfig(
                        listOf("popp"),
                        30,
                        false,
                        tokenProvider,
                        AttestationConfig.software(),
                    ),
                    platformProductId =
                        PlatformProductId.AppleProductId(
                            PlatformProductId.PLATFORM_APPLE,
                            "macos",
                            listOf(),
                        ),
                ),
            )
        log.debug { "Created Zeta SDK client" }

        callPoppWebSocket(sdk)
        log.info { "Finished" }
    }

    private fun callPoppWebSocket(sdk: ZetaSdkClient) {
        val wsUrl = "wss://popp.dev.poppservice.de:443/popp/practitioner/api/v1/token-generation-ehc"
        try {
            runBlocking {
                sdk.ws(
                    targetUrl = wsUrl,
                    builder = {
                        disableServerValidation(true)
                        logging(
                            LogLevel.ALL,
                            object : Logger {
                                override fun log(message: String) {
                                    println("log:$message")
                                }
                            },
                        )
                    },
                    customHeaders = emptyMap(),
                ) {
                    log.info { "WebSocket connected to $wsUrl" }
                    probeWithBadMessage()
                }
            }
        } catch (e: Exception) {
            var cause: Throwable? = e
            while (cause != null) {
                log.error(cause) { "WebSocket session failed" }
                cause = cause.cause
            }
            echo("Failed to open WebSocket: ${e.message}")
        }
    }

    /**
     * Send a deliberately invalid message and log the server's reply, then close. Verifies the
     * registration/auth handshake produced a working session: the server has to decode our frame
     * (proves the secure channel is up) and respond with an error of its own choosing.
     */
    private suspend fun DefaultClientWebSocketSession.probeWithBadMessage() {
        val payload = """{"type":"NoSuchMessage"}"""
        try {
            log.info { "WS send: $payload" }
            send(Frame.Text(payload))
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        log.info { "WS reply: ${frame.readText()}" }
                        break
                    }
                    is Frame.Binary -> {
                        log.info { "WS reply (binary): ${frame.readBytes().size} bytes" }
                        break
                    }
                    is Frame.Close -> {
                        log.info { "WS closed by server before replying" }
                        break
                    }
                    else -> { /* ignore ping/pong */ }
                }
            }
        } finally {
            close()
        }
    }
}
