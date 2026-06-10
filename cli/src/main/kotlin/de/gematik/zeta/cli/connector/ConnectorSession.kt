package de.gematik.zeta.cli.connector

import de.gematik.connector.Dotkon
import de.gematik.connector.ConnectorClient
import de.gematik.connector.engine.okhttp.dotkonOkHttpClient
import de.gematik.connector.parseDotkon
import de.gematik.zeta.cli.http.applyProxy
import de.gematik.zeta.cli.http.applyProxyAuthenticator
import de.gematik.zeta.cli.http.installCurlieLogging
import de.gematik.zeta.cli.trace.HttpTracingPlugin
import de.gematik.zeta.cli.trace.Span
import de.gematik.zeta.cli.trace.Tracer
import de.gematik.zeta.sdk.network.http.client.config.ProxyConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.time.Duration

private val log = KotlinLogging.logger {}

/**
 * One Connector connection per CLI invocation, owned by whoever calls [openConnectorSession]
 * and shared between the SDK auth flow (SMC-B `ExternalAuthenticate`) and any extra calls a
 * command needs (`StartCardSession` / `SecureSendAPDU` / `StopCardSession` for popp,
 * `GetCards` for `connector get cards`, …).
 *
 * The wrapped [HttpClient] is built eagerly in [openConnectorSession] so a bogus .kon fails
 * fast; the actual `ConnectorClient` (with its SDS load) is created lazily via [connector]
 * and reused thereafter. A coroutine [Mutex] makes that one-shot init concurrency-safe under
 * SDK auth retries. [close] always closes the underlying [HttpClient] — calling it before
 * any [connector] access is a clean no-op, so commands whose tokens are warm pay zero
 * Connector round trips.
 */
internal class ConnectorSession(
    val dotkon: Dotkon,
    private val httpClient: HttpClient,
) : Closeable {
    private val mutex = Mutex()

    @Volatile
    private var cached: ConnectorClient? = null

    /**
     * Connect to the Connector (loads the SDS) on first call; reuse the result thereafter.
     * The first-call SDS load is wrapped in a `connector.connect` span so the underlying
     * `http.request /connector.sds` always has a named, visible parent in the trace tree
     * regardless of which caller (popp flow, SDK auth via [LazySubjectTokenProvider], …)
     * happened to trigger it.
     */
    suspend fun connector(): ConnectorClient =
        cached ?: mutex.withLock {
            cached ?: Tracer.spanSuspend("connector.connect", attrs = mapOf("url" to dotkon.url)) {
                ConnectorClient.connect(httpClient, dotkon)
            }.also { cached = it }
        }

    override fun close() {
        httpClient.close()
    }
}

/**
 * Wrap a Connector call in a `connector.<op>` span. Reusable across both standalone
 * `zeta connector …` commands and the popp flow, so popp's connector sub-operations
 * appear under the same span name format.
 */
internal suspend fun <T> ConnectorSession.traced(
    op: String,
    attrs: Map<String, Any?> = emptyMap(),
    block: suspend ConnectorSession.() -> T,
): T = Tracer.spanSuspend("connector.$op", attrs) { block() }

/**
 * Variant of [traced] that explicitly parents the span under [parent] instead of the
 * thread-local current span. Used inside long-lived blocks (e.g. `popp.connect`) to keep
 * short-lived sub-spans flat at the surrounding parent level rather than nesting deep —
 * mirrors how [Tracer.spanUnder] is used for the per-frame `popp.ws.*` spans. With
 * [parent] null (tracer off, or no parent captured) it falls back to [traced]'s
 * current-span behaviour.
 */
internal suspend fun <T> ConnectorSession.tracedUnder(
    parent: Span?,
    op: String,
    attrs: Map<String, Any?> = emptyMap(),
    block: suspend ConnectorSession.() -> T,
): T =
    if (parent != null) Tracer.spanUnder(parent, "connector.$op", attrs) { block() }
    else Tracer.spanSuspend("connector.$op", attrs) { block() }

/**
 * Parse the .kon at [konPath] and build the OkHttp client with the .kon's TLS + curlie
 * logging. Does **not** connect to the Connector — that happens lazily on the first
 * [ConnectorSession.connector] call. Callers resolve [konPath] via
 * `cliConfig.resolveSelectedKonFile()` so the `active`-file fallback (and its stale-pointer
 * error hint) is applied uniformly across commands.
 *
 * @param proxy when non-null, the same proxy used by the CLI's other HTTP clients is
 *   applied to the OkHttp engine, so SOAP traffic to the Connector traverses it as well.
 */
internal fun openConnectorSession(
    konPath: Path,
    connectTimeout: Duration,
    requestTimeout: Duration,
    proxy: ProxyConfig? = null,
): ConnectorSession {
    log.info { "Reading .kon from $konPath" }
    val dotkon = parseDotkon(konPath.readText())
    log.info { "Connector: ${dotkon.url}" }

    val httpClient = dotkonOkHttpClient(dotkon) {
        install(HttpTimeout) {
            connectTimeoutMillis = connectTimeout.inWholeMilliseconds
            requestTimeoutMillis = requestTimeout.inWholeMilliseconds
        }
        installCurlieLogging()
        install(HttpTracingPlugin)
        engine {
            proxy?.let {
                applyProxy(it)
                applyProxyAuthenticator(it)
            }
        }
    }

    return ConnectorSession(dotkon, httpClient)
}
