package de.gematik.zeta.cli.vsdm

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import de.gematik.zeta.catalog.CatalogException
import de.gematik.zeta.catalog.Environment
import de.gematik.zeta.catalog.ServiceDiscoveryClient
import de.gematik.zeta.catalog.environmentFromIssuer
import de.gematik.zeta.cli.client.POPP_HEADER_NAME
import de.gematik.zeta.cli.client.ZetaSessionCommand
import de.gematik.zeta.cli.client.applyCliHttpDefaults
import de.gematik.zeta.cli.client.originOf
import de.gematik.zeta.cli.client.withAslExpiryRetry
import de.gematik.zeta.cli.client.parseHeaderOption
import de.gematik.zeta.cli.http.logInnerAslResponse
import de.gematik.zeta.cli.output.renderJson
import de.gematik.zeta.cli.output.renderXml
import de.gematik.zeta.cli.state.claimString
import de.gematik.zeta.cli.storage.ProfileDb
import de.gematik.zeta.cli.storage.ProfileDbCatalogStore
import de.gematik.zeta.cli.storage.zetaProfilePath
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import de.gematik.zeta.stress.identity.PoppJwt
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

private val log = KotlinLogging.logger {}

// Placeholder ETag: never matches, so the server always returns the full bundle (200). Real
// conditional-request (If-None-Match) handling comes later.
private const val EMPTY_ETAG = "\"0000000000000000000000000000000000000000000000000000000000000000\""

private val JWT_SEGMENT = Regex("^[A-Za-z0-9_-]+$")

/**
 * A compact JWT — three non-empty base64url segments (`header.payload.signature`). base64url has no
 * `.` or `/`, so this cleanly separates a token from a file path (which typically has a different dot
 * count and/or path separators).
 */
internal fun looksLikeJwt(s: String): Boolean {
    val parts = s.split('.')
    return parts.size == 3 && parts.all { it.isNotEmpty() && JWT_SEGMENT.matches(it) }
}

/**
 * `zeta vsdm get <popp-token>` — read a patient's Versichertenstammdaten from just a PoPP token:
 * derive the environment from the token issuer, resolve the insurer's VSDM endpoint from the TI
 * service-discovery catalog (or take `--endpoint` verbatim), and issue the authenticated read (scope
 * `vsdservice`), forwarding the PoPP token. The bundle is rendered as JSON (colored on a TTY, plain
 * when piped).
 */
internal class VsdmGetCommand : ZetaSessionCommand("get") {
    private val tokenArg: String? by argument(
        name = "POPP-TOKEN",
        help = "A PoPP token (compact JWT) or a path to a file containing one — auto-detected. " +
            "Falls back to the ZETA_POPP_TOKEN environment variable.",
    ).optional()

    private val endpointOverride: String? by option(
        "--endpoint",
        metavar = "URL",
        help = "Skip service-discovery routing and read from this VSDM endpoint. Base URL only " +
            "(scheme + host[:port]); any path is ignored — the standard VSDM path is appended.",
    )

    private val profileVersion: String by option(
        "--profile-version",
        metavar = "VERSION",
        help = "VSDM profile version requested from the endpoint (profileVersion query parameter).",
    ).default("1.1")

    private val requestHeaders: List<String> by option(
        "-H", "--header",
        metavar = "NAME: VALUE",
        envvar = "ZETA_VSDM_HEADER",
        help = "Override or add an inner VSDM request header ('Name: Value'). Replaces the built-in " +
            "Accept / If-None-Match / PoPP defaults by name (case-insensitive); does not affect the " +
            "outer ASL (CBOR) transport. Repeatable. (env: ZETA_VSDM_HEADER)",
    ).multiple()

    // Automation-friendly output: dump the full response like curl -i (status line, headers, blank
    // line, body) so a pipe carries the headers (ETag, PZ, …) alongside the body. Mirrors `zeta http -i`.
    private val include: Boolean by option(
        "-i", "--include",
        envvar = "ZETA_VSDM_INCLUDE",
        help = "Print the response as an HTTP message: status line, headers, a blank line, then the " +
            "body. Lets a script read ETag / PZ alongside the bundle. (env: ZETA_VSDM_INCLUDE)",
    ).flag(default = false)

    // Sign as the SMC-B that obtained the PoPP token (its actorId), so `--auth-db-telematik-id` is
    // never needed for `zeta vsdm get --auth-method db`.
    private var poppActorId: String? = null
    override val dbTelematikIdOverride: String? get() = poppActorId

    override fun help(context: Context) =
        "Read a patient's VSDM bundle: resolve the endpoint from a PoPP token (or --endpoint) and fetch it."

    override fun runCommand() {
        val input = tokenArg ?: System.getenv("ZETA_POPP_TOKEN")
            ?: throw UsageError("provide a PoPP token (or a file containing one) as an argument or via the ZETA_POPP_TOKEN environment variable")
        // Auto-detect: a compact JWT is three base64url segments (its payload is base64'd JSON);
        // anything else is taken as a path to a file that holds one.
        val token = if (looksLikeJwt(input)) {
            input
        } else {
            val file = java.io.File(input)
            if (!file.isFile) {
                throw UsageError("'$input' is neither a PoPP token (compact JWT) nor a readable file")
            }
            file.readText().trim()
        }

        val claims = PoppJwt.parse(token)
            ?: throw CliktError("could not parse the PoPP token — not a valid compact JWT")
        log.debug {
            "PoPP token: actorId=${claims.actorId} insurerId=${claims.insurerId} iss=${claims.iss} " +
                "(token ${token.take(12)}…)"
        }
        poppActorId = claims.actorId
        log.info { "acting SMC-B identity (from PoPP actorId): ${claims.actorId}" }
        val iss = claims.iss ?: throw CliktError("PoPP token has no 'iss' claim; cannot determine environment")
        val env = environmentFromIssuer(iss)
            ?: throw CliktError("cannot determine environment from PoPP issuer '$iss'")
        // The ASL environment follows the token's environment — a dev/ref/test token must use the
        // non-prod ASL, never the production one, regardless of the global --asl-prod flag.
        cliConfig.aslProdEnvironment = env == Environment.PROD
        log.info { "environment: ${env.name.lowercase()} (from issuer $iss); ASL prod=${cliConfig.aslProdEnvironment}" }

        val baseUrl = resolveBaseUrl(env, claims.insurerId)

        val targetUrl = baseUrl.trimEnd('/') + VSDM_PATH + "?profileVersion=$profileVersion"
        val resource = originOf(targetUrl)

        openSession(resource = resource, scopes = listOf("vsdservice")) { sdk, _ ->
            val client = sdk.httpClient { applyCliHttpDefaults(cliConfig) }
            try {
                runBlocking {
                    log.info { "GET $targetUrl (scope vsdservice)" }
                    // Ktor's header(...) appends, so a user override of e.g. Accept would send
                    // two values — key by lower-cased name so an override replaces the default.
                    val headers = LinkedHashMap<String, Pair<String, String>>()
                    fun putHeader(name: String, value: String) { headers[name.lowercase()] = name to value }
                    putHeader(HttpHeaders.Accept, "application/fhir+json")
                    putHeader(HttpHeaders.IfNoneMatch, EMPTY_ETAG)
                    putHeader(POPP_HEADER_NAME, token)
                    requestHeaders.map(::parseHeaderOption).forEach { (n, v) -> putHeader(n, v) }

                    val response = withAslExpiryRetry {
                        client.request(targetUrl) {
                            method = HttpMethod.Get
                            headers.values.forEach { (n, v) -> header(n, v) }
                        }
                    }
                    log.info { "response: HTTP ${response.status.value}" }
                    // The wire logger only sees the encrypted ASL envelope; surface the decrypted
                    // inner response too, so `-vv` shows it like the (already-logged) inner request.
                    if (!response.isPlainResponse()) {
                        logInnerAslResponse(
                            response.status.value,
                            response.status.description,
                            response.headers,
                            response.bodyAsBytes(),
                        )
                    }
                    renderResponse(response)
                }
            } finally {
                client.close()
            }
        }

        // The person reading the VSD should be the one who proved patient presence: the access token's
        // subject must match the PoPP token's actor. Warn (don't fail) on a mismatch.
        val authSubject = runCatching { loadEntry(resource).accessToken?.claimString("sub") }.getOrNull()
        if (authSubject != null && authSubject != claims.actorId) {
            log.warn { "PoPP actorId ${claims.actorId} does not match the authenticated Zeta identity $authSubject" }
        }
    }

    /**
     * The VSDM base URL to read from: `--endpoint` (routing override, normalized to its origin) when
     * given, otherwise the insurer's endpoint resolved from the service-discovery catalog for [env].
     */
    private fun resolveBaseUrl(env: Environment, insurerId: String): String {
        endpointOverride?.let { override ->
            val origin = originOf(override)
            log.info { "VSDM endpoint (explicit --endpoint, routing skipped): $origin" }
            return origin
        }

        val catalog = try {
            val store = ProfileDbCatalogStore(ProfileDb(zetaProfilePath(profile)))
            val client = ServiceDiscoveryClient(cliConfig.httpClient, store)
            runBlocking { client.fetchCatalog(env) }
        } catch (e: CatalogException) {
            throw CliktError(e.message ?: "service-discovery catalog fetch failed")
        }
        if (catalog.env != null && !catalog.env.equals(env.name, ignoreCase = true)) {
            log.warn { "catalog env '${catalog.env}' does not match the environment '${env.name.lowercase()}' detected from the token" }
        }
        val resolved = catalog.vsdmBaseUrl(insurerId)
            ?: throw CliktError("no VSDM endpoint for insurer $insurerId in the ${env.name.lowercase()} catalog")
        log.info { "VSDM endpoint for insurer $insurerId: $resolved" }
        return resolved
    }

    private suspend fun renderResponse(response: ZetaHttpResponse) {
        val status = response.status.value
        val contentType = response.contentType()
        val bytes = response.bodyAsBytes()

        // A non-2xx VSDM read is a failure, not a result — fail the command (non-zero exit)
        // with the server's reason rather than silently printing the body and exiting 0.
        if (status !in 200..299) {
            val reason = readableBody(bytes)?.let { ": $it" }
                ?: binaryNote(bytes, contentType)?.let { " ($it)" }
                ?: ""
            throw CliktError("VSDM request failed: HTTP $status ${response.status.description}$reason")
        }

        // `-i`: emit the whole thing as an HTTP message (status line, headers, blank line, body) so a
        // pipe carries the headers alongside the body. The body is left raw — a faithful response.
        if (include) {
            echo(httpResponseText(status, response.status.description, response.headers, bytes))
            return
        }

        if (bytes.isEmpty()) {
            echo("HTTP $status ${response.status.description} (empty body)")
            return
        }

        if (looksLikeJson(contentType)) {
            val text = bytes.decodeToString()
            val element = runCatching { Json.parseToJsonElement(text) }.getOrNull()
            if (element != null) {
                echo(renderJson(element, colorize = colorize))
                return
            }
            log.warn { "response Content-Type claims JSON but body is not parseable; printing raw" }
            echo(text)
            return
        }

        if (isFhirXml(contentType)) {
            echo(renderXml(bytes.decodeToString(), colorize = colorize))
            return
        }

        // Non-JSON success body. VSDM serves the bundle as application/cbor under newer
        // profiles; CBOR is not decoded here, so print the payload when it is actually text
        // and otherwise describe it — never dump raw binary as mangled UTF-8.
        echo(readableBody(bytes) ?: "<${binaryNote(bytes, contentType) ?: "empty body"}>")
    }

    private fun ZetaHttpResponse.contentType(): String =
        headers.entries.firstOrNull { it.key.equals(HttpHeaders.ContentType, ignoreCase = true) }
            ?.value
            .orEmpty()

    private fun looksLikeJson(contentType: String): Boolean =
        contentType.contains("application/json", ignoreCase = true) ||
            contentType.contains("+json", ignoreCase = true)

    private fun isFhirXml(contentType: String): Boolean =
        contentType.substringBefore(';').trim().equals("application/fhir+xml", ignoreCase = true)

    /** The body as text when it decodes to printable characters (e.g. a plain error string), else null. */
    private fun readableBody(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val text = bytes.decodeToString()
        val printable = text.none { it == '�' } &&
            text.all { it == '\n' || it == '\r' || it == '\t' || !it.isISOControl() }
        return if (printable) text.trim() else null
    }

    /** A one-line description of a non-text body (size + type), or null when empty. */
    private fun binaryNote(bytes: ByteArray, contentType: String): String? {
        if (bytes.isEmpty()) return null
        val type = contentType.substringBefore(';').ifBlank { "binary" }
        val cborHint = if (contentType.contains("cbor", ignoreCase = true)) "; CBOR decoding not supported" else ""
        return "${bytes.size}-byte $type body$cborHint"
    }
}
