package de.gematik.zeta.cli.serve

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import de.gematik.zeta.catalog.Environment
import de.gematik.zeta.catalog.ServiceCatalog
import de.gematik.zeta.catalog.ServiceDiscoveryClient
import de.gematik.zeta.cli.client.ZetaSessionCommand
import de.gematik.zeta.cli.client.originOf
import de.gematik.zeta.cli.connector.xdgCacheHome
import de.gematik.zeta.cli.connector.xdgRuntimeDir
import de.gematik.zeta.cli.popp.ConnectionType
import de.gematik.zeta.cli.popp.poppServiceUrlFor
import de.gematik.zeta.cli.state.enumerateEntries
import de.gematik.zeta.cli.state.profileStatusJson
import de.gematik.zeta.cli.storage.ProfileDb
import de.gematik.zeta.cli.storage.ProfileDbCatalogStore
import de.gematik.zeta.cli.storage.zetaProfilePath
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.unixConnector
import io.ktor.server.engine.embeddedServer
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

private val log = KotlinLogging.logger {}

private val LOOPBACK_HOSTS = setOf("127.0.0.1", "::1", "localhost")

/**
 * Default socket path: `$XDG_RUNTIME_DIR/zeta/zeta.sock` where the runtime dir exists (Linux — the
 * spec-correct, short, auto-cleaned home for sockets), else `<xdgCacheHome>/telematik/zeta/zeta.sock`.
 */
private fun defaultSocketPath(): Path =
    (xdgRuntimeDir()?.resolve("zeta") ?: xdgCacheHome().resolve("telematik/zeta")).resolve("zeta.sock")

/**
 * The `(resource, scopes)` set to warm: every VSDM instance in the [catalog] (scope `vsdservice`) plus
 * the PoPP service at [poppUrl] (scope `popp`), each mapped to its Zeta resource origin. Pure, so the
 * enumeration is unit-testable; a null catalog (discovery unreachable) yields just the PoPP endpoint.
 */
internal fun warmEndpoints(catalog: ServiceCatalog?, poppUrl: String): List<Pair<String, List<String>>> =
    buildList {
        catalog?.serviceInstances?.values
            ?.filter { it.type == "vsdm" }
            ?.map { originOf(it.url) }?.distinct()
            ?.forEach { add(it to listOf("vsdservice")) }
        add(originOf(poppUrl) to listOf("popp"))
    }

/**
 * `zeta serve` — a warm-session REST daemon. At startup it logs into the known Zeta-protected
 * endpoints for one TI environment (the catalog's VSDM instances + the PoPP service) so later
 * requests skip discover/register/authenticate (and, with `--auth-method connector`, the one-time
 * Konnektor SDS load + card enumeration). Warm-up is parallel + best-effort: endpoints that fail are
 * retried lazily on first use. Binds a unix socket (default) or `--port` TCP; serves `GET /api/health`,
 * `GET /api/zeta/status`, and the VSDM read endpoints under `/api/vsdm/`.
 */
class ServeCommand : ZetaSessionCommand(name = "serve") {
    private val socket: Path? by option(
        "--socket",
        metavar = "PATH",
        envvar = "ZETA_SOCKET",
        help = "Unix domain socket to listen on (mutually exclusive with --port). " +
            "Default: \$XDG_RUNTIME_DIR/zeta/zeta.sock, else ~/.cache/telematik/zeta/zeta.sock. (env: ZETA_SOCKET)",
    ).path(canBeDir = false)

    private val port: Int? by option(
        "--port",
        metavar = "PORT",
        envvar = "ZETA_PORT",
        help = "Listen on TCP instead of a unix socket (mutually exclusive with --socket). (env: ZETA_PORT)",
    ).int()

    private val host: String by option(
        "--host", "--bind",
        metavar = "ADDR",
        envvar = "ZETA_HOST",
        help = "Bind address for --port. Default: 127.0.0.1 (loopback only). (env: ZETA_HOST)",
    ).default("127.0.0.1")

    private val env: Environment by option(
        "--env",
        metavar = "ENV",
        envvar = "ZETA_ENV",
        help = "TI environment to warm and serve: dev (default), ref, test, or prod. " +
            "Selects the service-discovery catalog, the PoPP service URL, and ASL prod/non-prod. (env: ZETA_ENV)",
    ).enum<Environment>(ignoreCase = true).default(Environment.DEV)

    private val poppServiceUrlOverride: String? by option(
        "--popp-service-url",
        metavar = "URL",
        envvar = "ZETA_POPP_SERVICE_URL",
        help = "PoPP service to warm. Defaults to the URL derived from --env. (env: ZETA_POPP_SERVICE_URL)",
    )

    private val poppCard: PoppCardTransport by option(
        "--popp-card",
        metavar = "TRANSPORT",
        envvar = "ZETA_POPP_CARD",
        help = "Card transport for GET /api/vsdm/popp-then-read (PoPP): connector (eGK at the Konnektor, " +
            "requires --auth-method connector) or standard (eGK in a local PC/SC reader). Default: connector. " +
            "(env: ZETA_POPP_CARD)",
    ).enum<PoppCardTransport>(ignoreCase = true).default(PoppCardTransport.CONNECTOR)

    private val poppConnection: ConnectionType by option(
        "--popp-connection",
        metavar = "TYPE",
        envvar = "ZETA_POPP_CONNECTION",
        help = "Connector card connection: contact or contactless (--popp-card connector only). Default: contact. " +
            "(env: ZETA_POPP_CONNECTION)",
    ).enum<ConnectionType>(ignoreCase = true).default(ConnectionType.CONTACT)

    private val poppReader: String? by option(
        "--popp-reader",
        metavar = "NAME",
        envvar = "ZETA_POPP_READER",
        help = "PC/SC reader name substring for --popp-card standard. Default: the reader with a card inserted. " +
            "(env: ZETA_POPP_READER)",
    )

    private val poppWait: Long by option(
        "--popp-wait",
        metavar = "SECONDS",
        help = "Seconds to wait for a card in the reader (--popp-card standard). Default: 0 (fail immediately).",
    ).long().default(0)

    override fun help(context: Context) =
        "Run a warm-session REST daemon for one TI environment (unix socket by default, or --port for TCP)."

    /** The API surface, shared by the socket and TCP servers. */
    private fun Application.apiModule(ctx: DaemonContext) {
        // explicitNulls=false so absent fields (e.g. an unconfigured connector's identity) are omitted
        // rather than serialized as null.
        install(ContentNegotiation) { json(Json { explicitNulls = false }) }
        // Any daemon-originated error is JSON: uncaught exceptions become a 500 ErrorDto, matching the
        // explicit `respondError` sites. Forwarded upstream errors go through respondBytes (no exception),
        // so they keep their verbatim body + `middleware-error-source: upstream` — untouched here.
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                log.error(cause) { "unhandled error serving ${call.request.uri}" }
                respondError(call, HttpStatusCode.InternalServerError, "internal error: ${cause.message}")
            }
        }
        routing {
            get("/api/health") { handleHealth(call, ctx) }
            get("/api/zeta/status") {
                val path = zetaProfilePath(profile)
                val entries = enumerateEntries(ProfileDb(path))
                call.respond(profileStatusJson(profile, path, entries, reveal = false))
            }
            get("/api/vsdm/read") { handleVsdmRead(call, ctx) }
            get("/api/vsdm/popp-then-read") { handleVsdmPoppThenRead(call, ctx) }
            // Unmatched paths get a JSON 404 too (Ktor's default is plain text).
            route("{...}") { handle { respondError(call, HttpStatusCode.NotFound, "no such endpoint: ${call.request.uri}") } }
        }
    }

    override fun runCommand() {
        if (socket != null && port != null) {
            throw UsageError("--socket and --port are mutually exclusive; pass exactly one (or neither for the default socket)")
        }
        // Single-env daemon: the ASL prod/non-prod choice is fixed once here (each SDK client captures
        // it at build time), so there's no per-request mutation race.
        cliConfig.aslProdEnvironment = env == Environment.PROD

        // Build the identity once (fatal if the config is wrong: bad keystore, no card, unreadable DB).
        val (tokenProvider, connectorSession) = buildTokenProvider()

        // Connector card minting reuses the auth Konnektor session (same SMC-B behind both), so it's only
        // available under --auth-method connector; connectorSession is non-null exactly then.
        if (poppCard == PoppCardTransport.CONNECTOR && connectorSession == null) {
            throw UsageError("--popp-card connector requires --auth-method connector; use --popp-card standard otherwise")
        }
        val poppMint = PoppMintConfig(
            transport = poppCard,
            connection = poppConnection,
            reader = poppReader,
            waitSeconds = poppWait,
            serviceUrl = poppServiceUrlOverride ?: poppServiceUrlFor(env),
        )
        val ctx = DaemonContext(cliConfig, zetaProfilePath(profile), tokenProvider, connectorSession, env, poppMint)

        val summary = warmUp(ctx)

        if (port != null) serveTcp(ctx, port!!, summary) else serveUnix(ctx, (socket ?: defaultSocketPath()).toAbsolutePath(), summary)
    }

    private data class WarmSummary(val warmed: Int, val total: Int) {
        val deferred: Int get() = total - warmed
    }

    /** Parallel, best-effort login of the env's known endpoints; failures are deferred to lazy retry. */
    private fun warmUp(ctx: DaemonContext): WarmSummary = runBlocking {
        val path = zetaProfilePath(profile)
        val catalog = runCatching {
            ServiceDiscoveryClient(cliConfig.httpClient, ProfileDbCatalogStore(ProfileDb(path))).fetchCatalog(env)
        }.onFailure {
            log.warn { "service-discovery catalog fetch failed for ${env.name.lowercase()}: ${it.message}; VSDM endpoints will warm lazily" }
        }.getOrNull()

        val poppUrl = poppServiceUrlOverride ?: poppServiceUrlFor(env)
        val endpoints = warmEndpoints(catalog, poppUrl)
        log.info { "warming ${endpoints.size} endpoint(s) for env ${env.name.lowercase()}…" }

        val results = coroutineScope {
            endpoints.map { (resource, scopes) ->
                async {
                    runCatching { ctx.warmSessionFor(resource, scopes) }
                        .onSuccess { log.info { "warmed $resource $scopes" } }
                        .onFailure { log.warn { "warm $resource failed — will retry lazily: ${it.message}" } }
                }
            }.awaitAll()
        }
        WarmSummary(warmed = results.count { it.isSuccess }, total = results.size)
    }

    private fun ready(bind: String, summary: WarmSummary): String =
        "zeta serve ready (${summary.warmed}/${summary.total} warm, ${summary.deferred} lazy) — " +
            "listening on $bind (env ${env.name.lowercase()}, profile $profile) — Ctrl-C to stop"

    private fun serveTcp(ctx: DaemonContext, bindPort: Int, summary: WarmSummary) {
        if (host !in LOOPBACK_HOSTS) {
            log.warn { "binding $host:$bindPort — the REST API is unauthenticated; exposing it beyond loopback is at your own risk" }
        }
        val server = embeddedServer(CIO, host = host, port = bindPort) { apiModule(ctx) }.start(wait = false)
        Runtime.getRuntime().addShutdownHook(
            Thread {
                log.info { "zeta serve shutting down" }
                server.stop(500, 2000)
                ctx.close()
            },
        )
        echo(ready("http://$host:$bindPort", summary))
        CountDownLatch(1).await() // hold the foreground; the shutdown hook cleans up on Ctrl-C
    }

    private fun serveUnix(ctx: DaemonContext, sock: Path, summary: WarmSummary) {
        // AF_UNIX sun_path is ~104 bytes on macOS / 108 on Linux; fail early with a clear message.
        if (sock.toString().toByteArray().size >= 104) {
            throw UsageError("--socket path is too long for a unix socket (${sock.toString().length} chars, max ~103): $sock")
        }
        Files.createDirectories(sock.parent)
        Files.deleteIfExists(sock) // clear a stale socket left by a previous run

        val server = embeddedServer(CIO, configure = { unixConnector(sock.toString()) }) { apiModule(ctx) }.start(wait = false)

        // The CIO engine binds asynchronously; wait for the socket to exist before locking it down.
        var waitedMs = 0
        while (!Files.exists(sock) && waitedMs < 3000) {
            Thread.sleep(25); waitedMs += 25
        }
        if (!Files.exists(sock)) {
            server.stop(0, 0)
            ctx.close()
            throw CliktError("failed to bind unix socket $sock (see the log above for the cause)")
        }
        Files.setPosixFilePermissions(sock, PosixFilePermissions.fromString("rw-------"))
        Runtime.getRuntime().addShutdownHook(
            Thread {
                log.info { "zeta serve shutting down" }
                server.stop(500, 2000)
                Files.deleteIfExists(sock)
                ctx.close()
            },
        )
        echo(ready(sock.toString(), summary))
        CountDownLatch(1).await() // hold the foreground; the shutdown hook cleans up on Ctrl-C
    }
}
