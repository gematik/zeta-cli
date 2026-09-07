package de.gematik.zeta.cli.serve

import de.gematik.zeta.catalog.Environment
import de.gematik.zeta.catalog.ServiceDiscoveryClient
import de.gematik.zeta.cli.CliConfig
import de.gematik.zeta.cli.client.applyCliHttpDefaults
import de.gematik.zeta.cli.connector.ConnectorSession
import de.gematik.zeta.cli.client.originOf
import de.gematik.zeta.cli.lifecycle.ensureLoggedIn
import de.gematik.zeta.cli.popp.CardTransport
import de.gematik.zeta.cli.popp.PoppCardConfig
import de.gematik.zeta.cli.popp.runPoppFlow
import de.gematik.zeta.cli.sdk.buildZetaSdkClient
import de.gematik.zeta.cli.state.hasUsableCredentials
import de.gematik.zeta.cli.storage.ProfileDb
import de.gematik.zeta.cli.storage.ProfileDbCatalogStore
import de.gematik.zeta.cli.storage.SqliteSdkStorage
import de.gematik.zeta.cli.cache.CacheDb
import de.gematik.zeta.cli.vsdm.VsdmBundleCache
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.ZetaSdkClientExtension
import de.gematik.zeta.sdk.authentication.AuthenticationStorageImpl
import de.gematik.zeta.sdk.authentication.SubjectTokenProvider
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.storage.ResourceScope
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val log = KotlinLogging.logger {}

private const val REFRESH_LEAD_MS = 5_000L
private const val FALLBACK_POLL_MS = 30_000L
private const val FAILURE_BACKOFF_MS = 5_000L
private const val CACHE_PRUNE_INTERVAL_MS = 3_600_000L

/** Milliseconds to sleep before waking to refresh: [leadMs] before the token's [expSec] expiry, floored at 0. */
internal fun refreshDelayMs(expSec: Long, nowMs: Long, leadMs: Long): Long =
    (expSec * 1000 - leadMs - nowMs).coerceAtLeast(0)

/** A warm resource session: the SDK client plus its **one** reused HTTP client (built once). */
internal class WarmSession(val sdk: ZetaSdkClient, val http: ZetaHttpClient)

/**
 * The daemon's warm state for one TI [env]: one auth [tokenProvider] + an optional warm Konnektor
 * [connectorSession] (present only for `--auth-method connector`), and a per-(resource, scopes) cache
 * of [WarmSession]s. [warmSessionFor] is the single seam shared by the startup warm-sweep and the
 * request handlers: it returns a healthy cached session (SDK client + reused HTTP client), or lazily
 * builds/heals one. [requestMutex] serializes request handling (warm + serialized).
 */
internal class DaemonContext(
    private val cliConfig: CliConfig,
    private val storagePath: Path,
    private val tokenProvider: SubjectTokenProvider,
    val connectorSession: ConnectorSession?,
    val env: Environment,
    val poppMint: PoppCardConfig? = null,
    /** The shared cache file, or null when `--cache-db` was not given. Owned here, closed with the daemon. */
    private val cacheDb: CacheDb? = null,
) : Closeable {
    val requestMutex = Mutex()

    /** The VSDM bundles inside [cacheDb]; null when caching is off. */
    val vsdmCache: VsdmBundleCache? = cacheDb?.let { VsdmBundleCache(it) }

    /** Size of the cache file on disk, or null when caching is off. */
    fun cacheFileBytes(): Long? = cacheDb?.fileBytes()

    /** Warm-sweep progress, surfaced by `/api/health`. `warmupTotal` is null until the catalog resolves. */
    @Volatile
    var warmupComplete = false

    @Volatile
    var warmupTotal: Int? = null

    private data class SdkKey(val resource: String, val scopes: List<String>)

    private val cache = ConcurrentHashMap<SdkKey, WarmSession>()
    private val locks = ConcurrentHashMap<SdkKey, Mutex>()

    // Daemon-lifetime background scope: hosts the per-session token-refresh loops and the startup warm
    // sweep. Cancelled first in close() so nothing runs mid-teardown.
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshJobs = ConcurrentHashMap<SdkKey, Job>()

    /** Run the startup warm sweep off the ready path, on the daemon background scope. */
    fun launchWarmup(block: suspend CoroutineScope.() -> Unit): Job = refreshScope.launch(block = block)

    /**
     * Keep the cache within its bounds for as long as the daemon runs. Pruning only at startup would
     * leave a long-lived daemon holding personal data past `--cache-max-age-days` indefinitely — the
     * bounds exist for exactly that reason, so they have to be applied on the clock, not on boot.
     */
    fun launchCachePrune(maxEntries: Int, maxAgeDays: Int): Job? {
        val cache = vsdmCache ?: return null
        return refreshScope.launch {
            while (isActive) {
                val removed = withContext(Dispatchers.IO) {
                    cache.prune(System.currentTimeMillis() / 1000, maxEntries, maxAgeDays)
                }
                if (removed > 0) log.info { "cache prune removed $removed entr${if (removed == 1) "y" else "ies"}" }
                delay(CACHE_PRUNE_INTERVAL_MS)
            }
        }
    }

    /**
     * A warm session for (resource, scopes): a healthy cached one, or a freshly built + logged-in one.
     * The HTTP client is built **once** here (out of the request path). A cached-but-stale session is
     * re-authenticated *in place* — token refresh doesn't need a new client, so `http` is reused.
     */
    suspend fun warmSessionFor(resource: String, scopes: List<String>): WarmSession {
        val key = SdkKey(resource, scopes.sorted())
        return locks.computeIfAbsent(key) { Mutex() }.withLock {
            cache[key]?.let { session ->
                if (!isHealthy(session.sdk)) {
                    log.debug { "re-authenticating stale warm session for $resource" }
                    ensureLoggedIn(session.sdk)
                }
                return@withLock session
            }
            log.debug { "building warm session for $resource $scopes" }
            val sdk = buildZetaSdkClient(resource, scopes, storagePath, tokenProvider, cliConfig)
            ensureLoggedIn(sdk)
            val http = sdk.httpClient { applyCliHttpDefaults(cliConfig) }
            WarmSession(sdk, http).also { cache[key] = it; startRefreshJob(key, sdk) }
        }
    }

    /**
     * Proactively re-authenticate this session ~5s before its access token expires so an idle session
     * stays hot (a request never pays the refresh round-trip). The re-auth runs under [requestMutex] — the
     * same lock requests hold end-to-end — so it can't race an in-flight request on the token store.
     */
    private fun startRefreshJob(key: SdkKey, sdk: ZetaSdkClient) {
        refreshJobs[key] = refreshScope.launch {
            while (isActive) {
                val exp = accessTokenExpiry(key)
                if (exp <= 0L) {
                    delay(FALLBACK_POLL_MS)
                    continue
                }
                delay(refreshDelayMs(exp, System.currentTimeMillis(), REFRESH_LEAD_MS))
                val ok = requestMutex.withLock {
                    runCatching { sdk.authenticate().getOrThrow() }
                        .onSuccess { log.debug { "refreshed session ${key.resource} ${key.scopes}" } }
                        .onFailure { log.warn { "session refresh failed for ${key.resource}: ${it.message}" } }
                        .isSuccess
                }
                if (!ok) delay(FAILURE_BACKOFF_MS)
            }
        }
    }

    /** The session's access-token absolute expiry (epoch seconds) from its own auth storage, or 0 if none. */
    private suspend fun accessTokenExpiry(key: SdkKey): Long {
        val scope = ResourceScope(key.resource, key.scopes)
        val auth = AuthenticationStorageImpl(SqliteSdkStorage(scope.storageKey, ProfileDb(storagePath)), scope)
        return runCatching { auth.getTokenExpiration()?.toLongOrNull() }.getOrNull() ?: 0L
    }

    /** A warm session currently held open, projected for the health endpoint. */
    data class OpenSession(val resource: String, val scopes: List<String>)

    /** Snapshot of the currently-open warm sessions (a session is cached only after a successful login). */
    fun openSessions(): List<OpenSession> =
        cache.keys.map { OpenSession(it.resource, it.scopes) }.sortedBy { it.resource }

    /** The insurer's VSDM base URL from this env's catalog (cached), or null if unresolved. */
    suspend fun vsdmBaseUrl(insurerId: String): String? {
        val catalog = runCatching {
            ServiceDiscoveryClient(cliConfig.httpClient, ProfileDbCatalogStore(ProfileDb(storagePath))).fetchCatalog(env)
        }.getOrElse {
            log.warn { "catalog fetch failed for ${env.name.lowercase()}: ${it.message}" }
            return null
        }
        return catalog.vsdmBaseUrl(insurerId)
    }

    /**
     * Mint a fresh PoPP token over the warm popp session using the startup-configured card transport.
     * [egkHandle] selects the eGK for the connector flow (null → auto-select the single visible one); it
     * is irrelevant to the standard flow, where the reader's inserted card is the patient. Callers hold
     * [requestMutex] — the Konnektor / card is single-session. Throws if minting isn't enabled.
     */
    suspend fun mintPoppToken(egkHandle: String?): String {
        val mint = poppMint ?: error("PoPP is not enabled")
        val popp = warmSessionFor(originOf(mint.serviceUrl), listOf("popp"))
        val session = connectorSession.takeIf { mint.transport == CardTransport.CONNECTOR }
        return runPoppFlow(popp.sdk, mint.copy(egkHandle = egkHandle), session) {
            applyCliHttpDefaults(cliConfig)
        }
    }

    /** Cheap, DB-only health check: is the registration + access/refresh token usable? */
    private suspend fun isHealthy(sdk: ZetaSdkClient): Boolean =
        runCatching { sdk.status().getOrNull()?.hasUsableCredentials == true }.getOrDefault(false)

    override fun close() {
        // Stop the refresh loops before tearing down the SDK clients they call authenticate() on.
        refreshScope.cancel()
        refreshJobs.clear()
        // Closing the SDK client also closes its HTTP client (== the SDK's mainHttpClient), so don't
        // close WarmSession.http separately.
        cache.values.forEach { runCatching { ZetaSdkClientExtension.close(it.sdk) } }
        cache.clear()
        runCatching { connectorSession?.close() }
        runCatching { cacheDb?.close() }
        runCatching { cliConfig.httpClient.close() }
    }
}
