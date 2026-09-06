package de.gematik.zeta.cli.serve

import com.github.ajalt.clikt.core.UsageError
import de.gematik.zeta.catalog.Environment
import de.gematik.zeta.catalog.environmentFromIssuer
import de.gematik.zeta.cli.client.POPP_HEADER_NAME
import de.gematik.zeta.cli.client.originOf
import de.gematik.zeta.cli.client.withAslExpiryRetry
import de.gematik.zeta.cli.popp.PoppProtocolException
import de.gematik.zeta.cli.vsdm.VsdmBundleCache
import de.gematik.zeta.cli.vsdm.CacheDecision
import de.gematik.zeta.cli.vsdm.CachedBundle
import de.gematik.zeta.cli.vsdm.MIDDLEWARE_CACHE_HEADER
import de.gematik.zeta.cli.vsdm.MIDDLEWARE_INSURANT_ID_HEADER
import de.gematik.zeta.cli.vsdm.MIDDLEWARE_INSURER_ID_HEADER
import de.gematik.zeta.cli.vsdm.MIDDLEWARE_UPSTREAM_STATUS_HEADER
import de.gematik.zeta.cli.vsdm.VSDM_PATH
import de.gematik.zeta.cli.vsdm.VsdmCacheKey
import de.gematik.zeta.cli.vsdm.cacheDecision
import de.gematik.zeta.cli.vsdm.cacheKeyFor
import de.gematik.zeta.cli.vsdm.cacheOutcome
import de.gematik.zeta.cli.vsdm.normalizedContentType
import de.gematik.zeta.cli.vsdm.servesFromCache
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import de.gematik.zeta.stress.identity.PoppClaims
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

// What the read is keyed on when the client leaves it to the service — the same defaults the VSDM
// service applies, so a client that omits them shares cache entries with one that spells them out.
private const val DEFAULT_PROFILE_VERSION = "1.1"
private const val DEFAULT_ACCEPT = "application/fhir+json"

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

/**
 * The `middleware-*` metadata put on a forwarded read: who the bundle is about, which token proved
 * presence, what the cache did, and what the service actually answered — the last one matters because
 * a cache hit is reported to the client as `200` while upstream said `304`.
 */
internal fun middlewareResponseHeaders(
    claims: PoppClaims,
    poppToken: String,
    cacheOutcome: String,
    upstreamStatus: Int,
): List<Pair<String, String>> = buildList {
    add(MIDDLEWARE_POPP_HEADER to poppToken)
    add(MIDDLEWARE_INSURER_ID_HEADER to claims.insurerId)
    add(MIDDLEWARE_INSURANT_ID_HEADER to claims.patientId)
    add(MIDDLEWARE_CACHE_HEADER to cacheOutcome)
    add(MIDDLEWARE_UPSTREAM_STATUS_HEADER to upstreamStatus.toString())
    // A forwarded upstream error is the VSDM service's, not the daemon's — attribute it so the client can tell.
    if (upstreamStatus >= 400) add(MIDDLEWARE_ERROR_SOURCE_HEADER to ERROR_SOURCE_UPSTREAM)
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

    ctx.requestMutex.withLock {
        readAndForward(call, ctx, claims, baseUrl, upstreamUrl, resource, token, forwardPoppHeader = false)
    }
}

/**
 * The read itself, shared by both endpoints. Picks the conditional request per [cacheDecision], calls
 * upstream, then either forwards the response verbatim or — when the daemon asked on its own behalf
 * and got a `304` — serves the cached bundle as the `200` the client would otherwise have received.
 *
 * The caller holds [DaemonContext.requestMutex]: `popp-then-read` needs mint and read under one lock,
 * and the mutex is not reentrant.
 */
private suspend fun readAndForward(
    call: ApplicationCall,
    ctx: DaemonContext,
    claims: PoppClaims,
    baseUrl: String,
    upstreamUrl: String,
    resource: String,
    token: String,
    forwardPoppHeader: Boolean,
) {
    val cache = ctx.vsdmCache
    val profileVersion = call.request.queryParameters["profileVersion"] ?: DEFAULT_PROFILE_VERSION
    val key = normalizedContentType(call.request.headers[HttpHeaders.Accept] ?: DEFAULT_ACCEPT)
        ?.let { cacheKeyFor(claims, ctx.env, originOf(baseUrl), profileVersion, it) }
    // JDBC blocks, and a Ktor handler runs on the engine's dispatcher — keep the driver off it.
    val stored = key?.let { k -> cache?.let { withContext(Dispatchers.IO) { it.lookup(k) } } }
    val decision = cacheDecision(
        clientIfNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch],
        cachedEtag = stored?.etag,
        cacheControl = call.request.headers[HttpHeaders.CacheControl],
        cacheEnabled = cache != null && key != null,
    )

    // Client headers go up as-is; only If-None-Match may be substituted, and only when the client
    // asked no conditional question of its own.
    val forward = forwardableUpstreamHeaders(call.request.headers)
        .filterNot { it.first.equals(HttpHeaders.IfNoneMatch, ignoreCase = true) }
        .filterNot { forwardPoppHeader && it.first.equals(POPP_HEADER_NAME, ignoreCase = true) }

    val response = try {
        val session = ctx.warmSessionFor(resource, listOf("vsdservice"))
        log.info { "GET $upstreamUrl (scope vsdservice)" }
        withAslExpiryRetry {
            session.http.request(upstreamUrl) {
                method = HttpMethod.Get
                forward.forEach { (n, v) -> header(n, v) }
                decision.ifNoneMatch?.let { header(HttpHeaders.IfNoneMatch, it) }
                if (forwardPoppHeader) header(POPP_HEADER_NAME, token)
            }
        }
    } catch (e: Exception) {
        log.warn(e) { "VSDM read failed for $resource" }
        return respondError(call, HttpStatusCode.BadGateway, "VSDM read failed: ${e.message}")
    }

    val served = recordCacheResult(cache, key, decision, stored, response, token)
    forwardResponse(call, response, token, claims, served, key?.contentType, cacheOutcome(decision.intent, response.status.value))
}

/** Update the cache from [response]; return the stored bundle when it is the one to serve. */
private suspend fun recordCacheResult(
    cache: VsdmBundleCache?,
    key: VsdmCacheKey?,
    decision: CacheDecision,
    stored: CachedBundle?,
    response: ZetaHttpResponse,
    poppToken: String,
): CachedBundle? {
    if (cache == null || key == null) return null
    val now = System.currentTimeMillis() / 1000
    withContext(Dispatchers.IO) {
        when (response.status.value) {
            in 200..299 -> {
                val etag = response.headers.entries
                    .firstOrNull { it.key.equals(HttpHeaders.ETag, ignoreCase = true) }?.value
                if (etag != null) {
                    cache.store(key, CachedBundle(etag, response.bodyAsBytes(), poppToken, now, now))
                }
            }

            HttpStatusCode.NotModified.value -> cache.touch(key, now, poppToken)
            HttpStatusCode.NotFound.value, HttpStatusCode.Gone.value -> cache.invalidate(key)
        }
    }
    return stored.takeIf { servesFromCache(decision.intent, response.status.value) }
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
    val queryString = call.request.queryString()

    ctx.requestMutex.withLock {
        val token = when (val minted = mintPoppToken(ctx, egkHandle)) {
            is MintResult.Ok -> minted.token
            is MintResult.Failed -> return respondError(call, minted.status, minted.message)
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

        // The daemon owns the PoPP header here (it minted the token), so any client-supplied one is
        // dropped and replaced. Only the read is retried on ASL expiry — never the mint above, which
        // opened a card session.
        readAndForward(call, ctx, claims, baseUrl, upstreamUrl, resource, token, forwardPoppHeader = true)
    }
}

/** Outcome of a card-backed PoPP mint: the token, or the status and message to answer with. */
internal sealed interface MintResult {
    data class Ok(val token: String) : MintResult
    data class Failed(val status: HttpStatusCode, val message: String) : MintResult
}

/**
 * Mint a PoPP token via the startup-configured card transport. Shared by `GET /api/popp/token` and
 * `GET /api/vsdm/popp-then-read`; the caller holds [DaemonContext.requestMutex], since the Konnektor
 * and the card are single-session.
 */
internal suspend fun mintPoppToken(ctx: DaemonContext, egkHandle: String?): MintResult =
    try {
        MintResult.Ok(ctx.mintPoppToken(egkHandle))
    } catch (e: UsageError) {
        MintResult.Failed(HttpStatusCode.Conflict, e.message ?: "no eGK available")
    } catch (e: CardException) {
        MintResult.Failed(HttpStatusCode.Conflict, e.message ?: "no card in reader")
    } catch (e: PoppProtocolException) {
        MintResult.Failed(HttpStatusCode.BadGateway, "PoPP failed: ${e.message}")
    } catch (e: Exception) {
        log.warn(e) { "PoPP failed" }
        MintResult.Failed(HttpStatusCode.BadGateway, "PoPP failed: ${e.message}")
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

private suspend fun forwardResponse(
    call: ApplicationCall,
    response: ZetaHttpResponse,
    poppToken: String,
    claims: PoppClaims,
    served: CachedBundle?,
    servedContentType: String?,
    cacheOutcome: String,
) {
    // Serving from cache answers a question the client did not ask conditionally, so it gets the 200
    // it would have received without a cache: our body, and every header from the live 304 — the PZ
    // in particular is per-read and must never come from the cache.
    val upstreamContentType = response.headers.entries
        .firstOrNull { it.key.equals(HttpHeaders.ContentType, ignoreCase = true) }?.value
    val contentType = (served?.let { servedContentType } ?: upstreamContentType)
        ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
    response.headers.forEach { (name, value) ->
        if (name.lowercase() !in RESPONSE_SKIP) runCatching { call.response.headers.append(name, value) }
    }
    middlewareResponseHeaders(claims, poppToken, cacheOutcome, response.status.value)
        .forEach { (name, value) -> call.response.headers.append(name, value) }

    val status = if (served != null) HttpStatusCode.OK else response.status
    call.respondBytes(served?.body ?: response.bodyAsBytes(), contentType, status)
}
