package de.gematik.zeta.cli.connector

import com.github.ajalt.clikt.core.UsageError
import de.gematik.connector.Dotkon
import de.gematik.connector.ConnectorClient
import de.gematik.connector.engine.okhttp.dotkonOkHttpClient
import de.gematik.connector.parseDotkon
import de.gematik.zeta.cli.http.installCurlieLogging
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
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

    /** Connect to the Connector (loads the SDS) on first call; reuse the result thereafter. */
    suspend fun connector(): ConnectorClient =
        cached ?: mutex.withLock {
            cached ?: ConnectorClient.connect(httpClient, dotkon).also { cached = it }
        }

    override fun close() {
        httpClient.close()
    }
}

/**
 * Resolve a `.kon` by name and build the OkHttp client with the .kon's TLS + curlie
 * logging. Does **not** connect to the Connector — that happens lazily on the first
 * [ConnectorSession.connector] call. Throws a clean [UsageError] when the .kon can't be
 * found.
 */
internal fun openConnectorSession(
    connectorConfigName: String,
    connectTimeout: Duration,
    requestTimeout: Duration,
): ConnectorSession {
    val konPath = try {
        resolveKonFile(connectorConfigName)
    } catch (e: Exception) {
        throw UsageError(e.message ?: "could not resolve connector config")
    }
    log.info { "Reading .kon from $konPath" }
    val dotkon = parseDotkon(konPath.readText())
    log.info { "Connector: ${dotkon.url}" }

    val httpClient = dotkonOkHttpClient(dotkon) {
        install(HttpTimeout) {
            connectTimeoutMillis = connectTimeout.inWholeMilliseconds
            requestTimeoutMillis = requestTimeout.inWholeMilliseconds
        }
        installCurlieLogging()
    }

    return ConnectorSession(dotkon, httpClient)
}
