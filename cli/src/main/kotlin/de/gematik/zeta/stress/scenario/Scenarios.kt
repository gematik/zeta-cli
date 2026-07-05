package de.gematik.zeta.stress.scenario

import de.gematik.zeta.sdk.SdkStatus
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.stress.db.IdentityStore
import de.gematik.zeta.stress.db.ClientRow
import de.gematik.zeta.stress.db.ClientStore
import de.gematik.zeta.stress.identity.PoppJwt
import de.gematik.zeta.stress.runner.Attempt
import de.gematik.zeta.stress.runner.DriverResult
import de.gematik.zeta.stress.runner.LoadShape
import de.gematik.zeta.stress.runner.Progress
import de.gematik.zeta.stress.runner.RateSchedule
import de.gematik.zeta.stress.runner.Rates
import de.gematik.zeta.stress.runner.Reporter
import de.gematik.zeta.stress.runner.Snapshot
import de.gematik.zeta.stress.runner.runContinuous
import de.gematik.zeta.stress.runner.runLoad
import de.gematik.zeta.stress.sdk.HttpSettings
import de.gematik.zeta.stress.sdk.StressSdkClientFactory
import de.gematik.zeta.stress.sdk.applyStressHttp
import de.gematik.zeta.stress.storage.StateExpiry
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private val log = KotlinLogging.logger {}

class ScenarioDeps(
    val identityStore: IdentityStore,
    val clientStore: ClientStore,
    val stateExpiry: StateExpiry,
    val factory: StressSdkClientFactory,
    val http: HttpSettings,
    val reporter: Reporter,
    val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    // Wall-clock cap per attempt. A single client that stalls (e.g. the AS hangs while racing
    // to create a shared SMC-B user) must not freeze the whole batch — it's recorded as a failure.
    val attemptTimeoutMs: Long = 120_000,
    // When set (login-and-vsdm-storm), each attempt makes this authenticated request instead of a
    // bare authenticate(), driving the full cold chain (token exchange + ASL handshake + read).
    val request: VsdmRequest? = null,
    val poppPicker: PoppPicker? = null,
)

/**
 * Preflight: for the first [identities] identities, register a fuzzy `rand(clientsMin..clientsMax)`
 * OAuth clients each (top-up — existing registrations count toward the target). Each new client
 * gets a fresh `client_ref` namespace, runs `discover()` + `register()`, and is recorded.
 */
fun preflight(
    deps: ScenarioDeps,
    identities: Int,
    clientsMin: Int,
    clientsMax: Int,
    resource: String,
    scopes: List<String>,
    concurrency: Int,
    seed: Long?,
    progress: Progress? = null,
) {
    val rng = seed?.let { Random(it) } ?: Random.Default
    data class Work(val telematikId: String, val clientRef: String)

    val work = deps.identityStore.ids(identities).flatMap { telematikId ->
        val target = rng.nextInt(clientsMin, clientsMax + 1)
        val existing = deps.clientStore.countForIdentity(telematikId, resource)
        (existing until target).map { Work(telematikId, UUID.randomUUID().toString()) }
    }
    log.info { "Preflight: ${work.size} new clients across $identities identities" }

    val runStart = deps.clockMs()
    runBlocking {
        val ticker = progress?.let { p ->
            launch {
                while (isActive) {
                    delay(1000)
                    p.tickCount(deps.clockMs() - runStart, deps.reporter.completed, work.size, deps.reporter.window(3000))
                }
            }
        }
        runLoad(work, concurrency, LoadShape.Burst, deps.clockMs) { w ->
            val identity = deps.identityStore.get(w.telematikId) ?: return@runLoad
            val sdk = deps.factory.build(w.clientRef, identity, resource, scopes)
            val start = deps.clockMs()
            try {
                withTimeout(deps.attemptTimeoutMs) {
                    sdk.discover().getOrThrow()
                    sdk.register().getOrThrow()
                    val status = sdk.status().getOrThrow()
                    deps.clientStore.insert(
                        ClientRow(w.clientRef, w.telematikId, resource, scopes, deps.clockMs(), status.name),
                    )
                    deps.reporter.record(Attempt("register", deps.clockMs() - start, true, null, w.clientRef, w.telematikId))
                }
            } catch (e: TimeoutCancellationException) {
                deps.reporter.record(Attempt("register", deps.clockMs() - start, false, "timed out after ${deps.attemptTimeoutMs}ms", w.clientRef, w.telematikId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                deps.reporter.record(Attempt("register", deps.clockMs() - start, false, e.errorLabel(), w.clientRef, w.telematikId))
            } finally {
                sdk.closeQuietly()
            }
        }
        ticker?.cancelAndJoin()
        progress?.tickCount(deps.clockMs() - runStart, deps.reporter.completed, work.size, deps.reporter.window(3000))
    }
    progress?.close()
}

/** What to wipe from a client's state before an attempt, to force real token-endpoint load. */
enum class Expire { NONE, ACCESS_ONLY, ALL }

/**
 * Runs one attempt against an already-built [sdk], optionally expiring state first, and records the
 * outcome. When [ScenarioDeps.request] is null it's a bare `authenticate()`, verified by re-reading
 * `status()` (the SDK can report success without actually holding a token). When a request is set
 * (login-and-vsdm-storm) it issues that one authenticated request on [http] instead — the SDK
 * orchestrator runs the whole cold chain (token exchange + ASL handshake) and the read, and a
 * cached PoPP token is attached as the `PoPP` header. A stall is capped by
 * [ScenarioDeps.attemptTimeoutMs] and recorded as a failure.
 */
private suspend fun runAttempt(deps: ScenarioDeps, sdk: ZetaSdkClient, http: ZetaHttpClient?, row: ClientRow, expire: Expire) {
    val start = deps.clockMs()
    val req = deps.request
    val op = req?.opLabel ?: "authenticate"
    // httpStatus is null for the login-only path (authenticate has no HTTP response); the VSDM path fills it.
    fun rec(ok: Boolean, error: String?, httpStatus: Int? = null) =
        deps.reporter.record(Attempt(op, deps.clockMs() - start, ok, error, row.clientRef, row.telematikId, httpStatus))
    try {
        withTimeout(deps.attemptTimeoutMs) {
            when (expire) {
                Expire.ALL -> deps.stateExpiry.expireAll(row.clientRef)
                Expire.ACCESS_ONLY -> deps.stateExpiry.expireAccessTokenOnly(row.clientRef)
                Expire.NONE -> Unit
            }
            if (req == null) {
                val status = sdk.status().getOrThrow()
                if (status == SdkStatus.NOT_REGISTERED) {
                    sdk.discover().getOrThrow()
                    sdk.register().getOrThrow()
                }
                sdk.authenticate().getOrThrow()
                val finalStatus = sdk.status().getOrThrow()
                if (finalStatus == SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN) {
                    rec(true, null)
                } else {
                    rec(false, "no access token after authenticate (status=$finalStatus)")
                }
                return@withTimeout
            }

            val popp = if (req.popp) deps.poppPicker?.next(row.telematikId) else null
            if (req.popp && popp == null) {
                rec(false, "no PoPP token for identity ${row.telematikId}")
                return@withTimeout
            }
            if (http == null) {
                rec(false, "no http client")
                return@withTimeout
            }
            val resp = http.request(req.url) {
                method = HttpMethod.parse(req.method)
                req.headers.forEach { (k, v) -> header(k, v) }
                popp?.let { header("PoPP", it) }
            }
            val code = resp.status.value
            if (code !in req.expectStatus) {
                rec(false, "status=$code", code)
                return@withTimeout
            }
            // A 200 must return the insurant carried by the PoPP token we sent — which is
            // impossible unless the access token (nonce → SMC-B sign → token exchange), the ASL
            // handshake, and PoPP acceptance all succeeded. A green status alone doesn't prove that.
            val kvnr = popp?.let { PoppJwt.parse(it)?.patientId }
            if (code == 200 && kvnr != null && kvnr !in resp.bodyAsText()) {
                rec(false, "kvnr $kvnr not in FHIR body", code)
            } else {
                rec(true, null, code)
            }
        }
    } catch (e: TimeoutCancellationException) {
        rec(false, "timed out after ${deps.attemptTimeoutMs}ms")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        rec(false, e.errorLabel())
    }
}

/**
 * Waveform load: drive the cohort for [durationMs] following a time-varying [schedule] (see
 * [Rates.profile]) — e.g. warm-up → burst → slow-down → spikes → repeat. Same machinery as [soak];
 * only the admission-rate curve differs.
 */
fun profile(
    deps: ScenarioDeps,
    resource: String,
    clients: List<ClientRow>,
    concurrency: Int,
    schedule: RateSchedule,
    durationMs: Long,
    expire: Expire,
    scopes: List<String>,
    onTick: (Long, Int, Snapshot) -> Unit,
) {
    require(clients.isNotEmpty()) { "no registered clients for $resource — run preflight first" }
    log.info { "Profile: ${clients.size} clients for ${durationMs / 1000}s, expire=$expire" }

    ClientPool(deps, resource, scopes).use { pool ->
        runBlocking {
            runContinuous(
                items = clients,
                concurrency = concurrency,
                schedule = schedule,
                durationMs = durationMs,
                clockMs = deps.clockMs,
                reporter = deps.reporter,
                onTick = onTick,
            ) { row ->
                pool.get(row)?.let { sdk ->
                    runAttempt(deps, sdk, if (deps.request != null) pool.httpClient(row) else null, row, expire)
                }
            }
        }
    }
}

/**
 * Ramp to the breaking point: step the rate up from [startPerMin] by [stepPerMin] every
 * [stepEverySec], up to [maxPerMin] or [durationMs], stopping when the trailing window's failure
 * fraction exceeds [maxFailFraction] or its p99 exceeds [maxP99Ms]. Returns the peak healthy
 * throughput observed.
 */
fun ramp(
    deps: ScenarioDeps,
    resource: String,
    clients: List<ClientRow>,
    concurrency: Int,
    startPerMin: Int,
    stepPerMin: Int,
    stepEverySec: Long,
    maxPerMin: Int,
    durationMs: Long,
    maxFailFraction: Double,
    maxP99Ms: Long,
    expire: Expire,
    scopes: List<String>,
    onTick: (Long, Int, Snapshot) -> Unit,
): DriverResult {
    require(clients.isNotEmpty()) { "no registered clients for $resource — run preflight first" }
    log.info { "Ramp: $startPerMin→$maxPerMin/min (+$stepPerMin every ${stepEverySec}s), stop at fail>$maxFailFraction or p99>${maxP99Ms}ms" }

    return ClientPool(deps, resource, scopes).use { pool ->
        runBlocking {
            runContinuous(
                items = clients,
                concurrency = concurrency,
                schedule = Rates.ramp(startPerMin, stepPerMin, stepEverySec * 1000, maxPerMin),
                durationMs = durationMs,
                clockMs = deps.clockMs,
                reporter = deps.reporter,
                stopWhen = { w -> w.failFraction > maxFailFraction || w.p99 > maxP99Ms },
                onTick = onTick,
            ) { row ->
                pool.get(row)?.let { sdk ->
                    runAttempt(deps, sdk, if (deps.request != null) pool.httpClient(row) else null, row, expire)
                }
            }
        }
    }
}

/**
 * Reuses one [ZetaSdkClient] — and, for VSDM, one authenticated [ZetaHttpClient] — per client_ref
 * across many ops; closes them all on exit. Building an HTTP client per op would spawn a fresh
 * OkHttp engine each request and exhaust the box at fleet scale, so the client is cached and
 * reused; token/ASL state lives in `sdk_state`, so wiping those rows still forces a fresh cold
 * chain on the next request.
 */
private class ClientPool(
    private val deps: ScenarioDeps,
    private val resource: String,
    private val scopes: List<String>,
) : AutoCloseable {
    private val sdks = ConcurrentHashMap<String, ZetaSdkClient>()
    private val https = ConcurrentHashMap<String, ZetaHttpClient>()

    fun get(row: ClientRow): ZetaSdkClient? =
        sdks.getOrPut(row.clientRef) {
            val identity = deps.identityStore.get(row.telematikId) ?: return null
            deps.factory.build(row.clientRef, identity, resource, scopes.ifEmpty { row.scopes })
        }

    fun httpClient(row: ClientRow): ZetaHttpClient? {
        val sdk = get(row) ?: return null
        return https.getOrPut(row.clientRef) { sdk.httpClient { applyStressHttp(deps.http) } }
    }

    override fun close() {
        https.values.forEach { runCatching { it.close() } }
        sdks.values.forEach { it.closeQuietly() }
    }
}

private fun ZetaSdkClient.closeQuietly() {
    runCatching { runBlocking { close() } }
}

private fun Throwable.errorLabel(): String =
    "${this::class.simpleName}: ${message?.take(120) ?: ""}".trim()
