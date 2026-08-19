package de.gematik.zeta.cli.serve

import com.github.ajalt.clikt.core.UsageError
import de.gematik.zeta.catalog.Environment
import de.gematik.zeta.catalog.environmentFromIssuer
import de.gematik.zeta.cli.client.POPP_HEADER_NAME
import de.gematik.zeta.cli.client.originOf
import de.gematik.zeta.cli.client.withAslExpiryRetry
import de.gematik.zeta.cli.popp.PoppProtocolException
import de.gematik.zeta.cli.vsdm.VSDM_PATH
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import de.gematik.zeta.stress.identity.PoppJwt
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.queryString
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import javax.smartcardio.CardException
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val log = KotlinLogging.logger {}

@Serializable
internal data class ErrorDto(val error: String)

/**
 * The daemon's `middleware-*` response-header namespace: metadata surfaced on top of the verbatim
 * upstream response. `middleware-popp` echoes the PoPP token used/minted for the read; the client of
 * `/api/vsdm/popp-then-read` learns the minted token from it.
 */
internal const val MIDDLEWARE_POPP_HEADER = "middleware-popp"

/** Set on every error response to attribute the failing status to the daemon (`middleware`) or `upstream`. */
internal const val MIDDLEWARE_ERROR_SOURCE_HEADER = "middleware-error-source"

/** Optional request header (`middleware-*` control namespace) selecting the eGK card handle when minting. */
internal const val MIDDLEWARE_EGK_HEADER = "middleware-egk"

private const val ERROR_SOURCE_MIDDLEWARE = "middleware"
private const val ERROR_SOURCE_UPSTREAM = "upstream"

// Inbound client headers the daemon owns and must not forward upstream: framing / hop-by-hop, plus the
// transport auth the SDK's own client attaches (Authorization/Cookie).
private val INBOUND_SKIP = setOf("host", "content-length", "transfer-encoding", "connection", "authorization", "cookie")

// Upstream response headers Ktor frames itself; Content-Type is carried by respondBytes instead.
private val RESPONSE_SKIP = setOf("content-length", "transfer-encoding", "connection", "content-type", "host", "upgrade")

/** Null when the token env matches the daemon env, else the 409 message. */
internal fun envMismatchError(tokenEnv: Environment, daemonEnv: Environment): String? =
    if (tokenEnv == daemonEnv) {
        null
    } else {
        "PoPP token is for '${tokenEnv.name.lowercase()}', this daemon serves '${daemonEnv.name.lowercase()}'"
    }

/**
 * Client request headers forwarded to the upstream read as-is, minus framing/transport-auth the SDK owns
 * and the daemon's own `middleware-*` control headers (e.g. `middleware-egk`), which never leave the daemon.
 */
internal fun forwardableUpstreamHeaders(headers: Headers): List<Pair<String, String>> =
    buildList {
        headers.entries().forEach { (name, values) ->
            val lower = name.lowercase()
            if (lower !in INBOUND_SKIP && !lower.startsWith("middleware-")) values.forEach { add(name to it) }
        }
    }

/** `<base>/vsdservice/v1/vsdmbundle` with the client's query string forwarded verbatim. */
internal fun upstreamVsdmUrl(baseUrl: String, queryString: String): String =
    baseUrl.trimEnd('/') + VSDM_PATH + if (queryString.isNotEmpty()) "?$queryString" else ""

/**
 * `GET /api/vsdm/read` — a transparent VSD-read proxy. Reads the pre-minted PoPP token from the `PoPP`
 * request header, rejects a token whose environment ≠ the daemon's `--env` (409), resolves the insurer's
 * VSDM endpoint from the catalog, and reads the bundle over the warm session — forwarding the query
 * string + client headers upstream and the upstream status/headers/body back, plus a `middleware-popp`
 * response header echoing the token used.
 */
internal suspend fun handleVsdmRead(call: ApplicationCall, ctx: DaemonContext) {
    val token = call.request.headers[POPP_HEADER_NAME]
        ?: return respondError(call, HttpStatusCode.BadRequest, "missing '$POPP_HEADER_NAME' request header")
    val claims = PoppJwt.parse(token)
        ?: return respondError(call, HttpStatusCode.BadRequest, "could not parse the PoPP token — not a valid compact JWT")
    val iss = claims.iss
        ?: return respondError(call, HttpStatusCode.BadRequest, "PoPP token has no 'iss' claim")
    val tokenEnv = environmentFromIssuer(iss)
        ?: return respondError(call, HttpStatusCode.BadRequest, "cannot determine environment from PoPP issuer '$iss'")
    envMismatchError(tokenEnv, ctx.env)?.let { return respondError(call, HttpStatusCode.Conflict, it) }

    val baseUrl = ctx.vsdmBaseUrl(claims.insurerId)
        ?: return respondError(
            call,
            HttpStatusCode.NotFound,
            "no VSDM endpoint for insurer ${claims.insurerId} in the ${ctx.env.name.lowercase()} catalog",
        )
    val upstreamUrl = upstreamVsdmUrl(baseUrl, call.request.queryString())
    val resource = originOf(upstreamUrl)
    val forward = forwardableUpstreamHeaders(call.request.headers)

    val response = try {
        ctx.requestMutex.withLock {
            val session = ctx.warmSessionFor(resource, listOf("vsdservice"))
            log.info { "GET $upstreamUrl (scope vsdservice)" }
            withAslExpiryRetry {
                session.http.request(upstreamUrl) {
                    method = HttpMethod.Get
                    forward.forEach { (n, v) -> header(n, v) }
                }
            }
        }
    } catch (e: Exception) {
        log.warn(e) { "VSDM read failed for $resource" }
        return respondError(call, HttpStatusCode.BadGateway, "VSDM read failed: ${e.message}")
    }

    forwardResponse(call, response, token)
}

/**
 * `GET /api/vsdm/popp-then-read` — mint a PoPP token via the startup-configured card transport, then
 * read the bundle exactly like [handleVsdmRead]. The minted token is surfaced via `middleware-popp`;
 * an optional `middleware-egk` request header selects the eGK for the connector flow. Mint + read run
 * under one lock — the Konnektor / card is single-session and interleaving would break presence semantics.
 */
internal suspend fun handleVsdmPoppThenRead(call: ApplicationCall, ctx: DaemonContext) {
    if (ctx.poppMint == null) {
        return respondError(
            call,
            HttpStatusCode.NotImplemented,
            "PoPP not available; start zeta serve with --popp-card connector|standard",
        )
    }
    val egkHandle = call.request.headers[MIDDLEWARE_EGK_HEADER]
    // The daemon owns the PoPP header here (it mints the token), so drop any client-supplied one.
    val forward = forwardableUpstreamHeaders(call.request.headers)
        .filterNot { it.first.equals(POPP_HEADER_NAME, ignoreCase = true) }
    val queryString = call.request.queryString()

    ctx.requestMutex.withLock {
        val token = try {
            ctx.mintPoppToken(egkHandle)
        } catch (e: UsageError) {
            return respondError(call, HttpStatusCode.Conflict, e.message ?: "no eGK available")
        } catch (e: CardException) {
            return respondError(call, HttpStatusCode.Conflict, e.message ?: "no card in reader")
        } catch (e: PoppProtocolException) {
            return respondError(call, HttpStatusCode.BadGateway, "PoPP failed: ${e.message}")
        } catch (e: Exception) {
            log.warn(e) { "PoPP failed" }
            return respondError(call, HttpStatusCode.BadGateway, "PoPP failed: ${e.message}")
        }

        val claims = PoppJwt.parse(token)
            ?: return respondError(call, HttpStatusCode.BadGateway, "PoPP token could not be parsed")
        val baseUrl = ctx.vsdmBaseUrl(claims.insurerId)
            ?: return respondError(
                call,
                HttpStatusCode.NotFound,
                "no VSDM endpoint for insurer ${claims.insurerId} in the ${ctx.env.name.lowercase()} catalog",
            )
        val upstreamUrl = upstreamVsdmUrl(baseUrl, queryString)
        val resource = originOf(upstreamUrl)

        val response = try {
            val session = ctx.warmSessionFor(resource, listOf("vsdservice"))
            log.info { "GET $upstreamUrl (scope vsdservice) [minted]" }
            // Only the read is retried on ASL expiry — never the PoPP above (it opened a card session).
            withAslExpiryRetry {
                session.http.request(upstreamUrl) {
                    method = HttpMethod.Get
                    forward.forEach { (n, v) -> header(n, v) }
                    header(POPP_HEADER_NAME, token)
                }
            }
        } catch (e: Exception) {
            log.warn(e) { "VSDM read failed for $resource" }
            return respondError(call, HttpStatusCode.BadGateway, "VSDM read failed: ${e.message}")
        }

        forwardResponse(call, response, token)
    }
}

/**
 * Send an `ErrorDto` body tagged with `middleware-error-source: middleware` (a daemon-originated failure).
 * Writes the JSON directly with an explicit content type — going through ContentNegotiation would let a
 * client's `Accept: application/fhir+json` (which doesn't match `application/json`) turn the error into an
 * empty `406`, swallowing the body.
 */
internal suspend fun respondError(call: ApplicationCall, status: HttpStatusCode, message: String) {
    call.response.headers.append(MIDDLEWARE_ERROR_SOURCE_HEADER, ERROR_SOURCE_MIDDLEWARE)
    call.respondText(
        Json.encodeToString(ErrorDto.serializer(), ErrorDto(message)),
        ContentType.Application.Json,
        status,
    )
}

private suspend fun forwardResponse(call: ApplicationCall, response: ZetaHttpResponse, poppToken: String) {
    val contentType = response.headers.entries
        .firstOrNull { it.key.equals(HttpHeaders.ContentType, ignoreCase = true) }
        ?.value?.let { runCatching { ContentType.parse(it) }.getOrNull() }
    response.headers.forEach { (name, value) ->
        if (name.lowercase() !in RESPONSE_SKIP) runCatching { call.response.headers.append(name, value) }
    }
    call.response.headers.append(MIDDLEWARE_POPP_HEADER, poppToken)
    // A forwarded upstream error is the VSDM service's, not the daemon's — attribute it so the client can tell.
    if (response.status.value >= 400) call.response.headers.append(MIDDLEWARE_ERROR_SOURCE_HEADER, ERROR_SOURCE_UPSTREAM)
    call.respondBytes(response.bodyAsBytes(), contentType, response.status)
}
