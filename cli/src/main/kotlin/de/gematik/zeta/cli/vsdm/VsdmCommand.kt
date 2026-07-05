package de.gematik.zeta.cli.vsdm

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import de.gematik.zeta.catalog.CatalogException
import de.gematik.zeta.catalog.Environment
import de.gematik.zeta.catalog.ServiceDiscoveryClient
import de.gematik.zeta.catalog.environmentFromIssuer
import de.gematik.zeta.cli.client.POPP_HEADER_NAME
import de.gematik.zeta.cli.client.ZetaSessionCommand
import de.gematik.zeta.cli.client.applyCliHttpDefaults
import de.gematik.zeta.cli.client.originOf
import de.gematik.zeta.cli.output.renderJson
import de.gematik.zeta.cli.state.claimString
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import de.gematik.zeta.stress.identity.PoppJwt
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

private val log = KotlinLogging.logger {}

private const val VSDM_PATH = "/vsdservice/v1/vsdmbundle?profileVersion=1.0"

// Placeholder ETag: never matches, so the server always returns the full bundle (200). Real
// conditional-request (If-None-Match) handling comes later.
private const val EMPTY_ETAG = "\"0000000000000000000000000000000000000000000000000000000000000000\""

/**
 * `zeta vsdm <popp-token>` — read a patient's Versichertenstammdaten from just a PoPP token: derive
 * the environment from the token issuer, resolve the insurer's VSDM endpoint from the TI
 * service-discovery catalog, and issue the authenticated read (scope `vsdservice`), forwarding the
 * PoPP token. The bundle is rendered as JSON (colored on a TTY, plain when piped).
 */
internal class VsdmCommand : ZetaSessionCommand("vsdm") {
    private val tokenArg: String? by argument(
        name = "POPP-TOKEN",
        help = "The PoPP token (compact JWT). Falls back to the ZETA_POPP_TOKEN environment variable.",
    ).optional()

    // Sign as the SMC-B that obtained the PoPP token (its actorId), so `--auth-db-telematik-id` is
    // never needed for `zeta vsdm --auth-method db`.
    private var poppActorId: String? = null
    override val dbTelematikIdOverride: String? get() = poppActorId

    override fun help(context: Context) =
        "Read a patient's VSDM bundle: resolve the endpoint from a PoPP token and fetch it."

    override fun runCommand() {
        val token = tokenArg ?: System.getenv("ZETA_POPP_TOKEN")
            ?: throw UsageError("provide a PoPP token as an argument or via the ZETA_POPP_TOKEN environment variable")

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

        val catalog = try {
            runBlocking { ServiceDiscoveryClient(cliConfig.httpClient).fetchCatalog(env) }
        } catch (e: CatalogException) {
            throw CliktError(e.message ?: "service-discovery catalog fetch failed")
        }
        if (catalog.env != null && !catalog.env.equals(env.name, ignoreCase = true)) {
            log.warn { "catalog env '${catalog.env}' does not match the environment '${env.name.lowercase()}' detected from the token" }
        }

        val baseUrl = catalog.vsdmBaseUrl(claims.insurerId)
            ?: throw CliktError("no VSDM endpoint for insurer ${claims.insurerId} in the ${env.name.lowercase()} catalog")
        log.info { "VSDM endpoint for insurer ${claims.insurerId}: $baseUrl" }

        val targetUrl = baseUrl.trimEnd('/') + VSDM_PATH
        val resource = originOf(targetUrl)

        openSession(resource = resource, scopes = listOf("vsdservice")) { sdk, _ ->
            val client = sdk.httpClient { applyCliHttpDefaults(cliConfig) }
            try {
                runBlocking {
                    log.info { "GET $targetUrl (scope vsdservice)" }
                    val response = client.request(targetUrl) {
                        method = HttpMethod.Get
                        header(HttpHeaders.Accept, "application/fhir+json")
                        header(HttpHeaders.IfNoneMatch, EMPTY_ETAG)
                        header(POPP_HEADER_NAME, token)
                    }
                    log.info { "response: HTTP ${response.status.value}" }
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

    private suspend fun renderResponse(response: ZetaHttpResponse) {
        if (response.status.value !in 200..299) {
            log.warn { "VSDM request returned HTTP ${response.status.value} ${response.status.description}" }
        }
        val body = response.bodyAsText()
        if (body.isEmpty()) {
            echo("HTTP ${response.status.value} ${response.status.description} (empty body)")
            return
        }
        val element = runCatching { Json.parseToJsonElement(body) }.getOrNull()
        if (element != null) {
            echo(renderJson(element, colorize = colorize))
        } else {
            log.warn { "response body is not JSON; printing raw" }
            echo(body)
        }
    }
}
