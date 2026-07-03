package de.gematik.zeta.stress.scenario

import de.gematik.zeta.sdk.SdkStatus
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.stress.db.CardStore
import de.gematik.zeta.stress.db.ClientRow
import de.gematik.zeta.stress.db.ClientStore
import de.gematik.zeta.stress.runner.Attempt
import de.gematik.zeta.stress.runner.DriverResult
import de.gematik.zeta.stress.runner.LoadShape
import de.gematik.zeta.stress.runner.Rates
import de.gematik.zeta.stress.runner.Reporter
import de.gematik.zeta.stress.runner.Snapshot
import de.gematik.zeta.stress.runner.runContinuous
import de.gematik.zeta.stress.runner.runLoad
import de.gematik.zeta.stress.sdk.StressSdkClientFactory
import de.gematik.zeta.stress.storage.StateExpiry
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private val log = KotlinLogging.logger {}

class ScenarioDeps(
    val cardStore: CardStore,
    val clientStore: ClientStore,
    val stateExpiry: StateExpiry,
    val factory: StressSdkClientFactory,
    val reporter: Reporter,
    val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    // Wall-clock cap per attempt. A single client that stalls (e.g. the AS hangs while racing
    // to create a shared SMC-B user) must not freeze the whole batch — it's recorded as a failure.
    val attemptTimeoutMs: Long = 120_000,
)

/**
 * Preflight: for the first [identities] cards, register a fuzzy `rand(clientsMin..clientsMax)`
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
) {
    val rng = seed?.let { Random(it) } ?: Random.Default
    data class Work(val cardId: String, val clientRef: String)

    val work = deps.cardStore.ids(identities).flatMap { cardId ->
        val target = rng.nextInt(clientsMin, clientsMax + 1)
        val existing = deps.clientStore.countForCard(cardId)
        (existing until target).map { Work(cardId, UUID.randomUUID().toString()) }
    }
    log.info { "Preflight: ${work.size} new clients across $identities identities" }

    runBlocking {
        runLoad(work, concurrency, LoadShape.Burst, deps.clockMs) { w ->
            val card = deps.cardStore.get(w.cardId) ?: return@runLoad
            val sdk = deps.factory.build(w.clientRef, card, resource, scopes)
            val start = deps.clockMs()
            try {
                withTimeout(deps.attemptTimeoutMs) {
                    sdk.discover().getOrThrow()
                    sdk.register().getOrThrow()
                    val status = sdk.status().getOrThrow()
                    deps.clientStore.insert(
                        ClientRow(w.clientRef, w.cardId, resource, scopes, deps.clockMs(), status.name),
                    )
                    deps.reporter.record(Attempt("register", deps.clockMs() - start, true, null))
                }
            } catch (e: TimeoutCancellationException) {
                deps.reporter.record(Attempt("register", deps.clockMs() - start, false, "timed out after ${deps.attemptTimeoutMs}ms"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                deps.reporter.record(Attempt("register", deps.clockMs() - start, false, e.errorLabel()))
            } finally {
                sdk.closeQuietly()
            }
        }
    }
}

/**
 * The core scenario. A cohort of registered clients whose tokens + ASL session are wiped
 * ([expire] = true) all attempt `authenticate()` under [shape] (Burst = thundering herd,
 * Steady = paced), capped at [concurrency].
 */
fun loginStorm(
    deps: ScenarioDeps,
    resource: String,
    cohort: Int,
    concurrency: Int,
    shape: LoadShape,
    expire: Boolean,
    scopes: List<String>,
) {
    val clients = deps.clientStore.cohort(resource, cohort)
    require(clients.isNotEmpty()) { "no registered clients for $resource — run preflight first" }
    if (expire) clients.forEach { deps.stateExpiry.expireAll(it.clientRef) }
    log.info { "Login storm: ${clients.size} clients, expire=$expire, shape=$shape" }

    runBlocking {
        runLoad(clients, concurrency, shape, deps.clockMs) { row ->
            authenticateOnce(deps, row, scopes.ifEmpty { row.scopes })
        }
    }
}

/**
 * Refresh churn: drop only the access token (keep the refresh token) for the cohort, then
 * `authenticate()` — exercising the `HAS_REFRESH_TOKEN` path rather than a fresh subject-token sign.
 */
fun refreshChurn(
    deps: ScenarioDeps,
    resource: String,
    cohort: Int,
    concurrency: Int,
    shape: LoadShape,
    scopes: List<String>,
) {
    val clients = deps.clientStore.cohort(resource, cohort)
    require(clients.isNotEmpty()) { "no registered clients for $resource — run preflight first" }
    clients.forEach { deps.stateExpiry.expireAccessTokenOnly(it.clientRef) }
    log.info { "Refresh churn: ${clients.size} clients, shape=$shape" }

    runBlocking {
        runLoad(clients, concurrency, shape, deps.clockMs) { row ->
            authenticateOnce(deps, row, scopes.ifEmpty { row.scopes })
        }
    }
}

/** What to wipe from a client's state before an attempt, to force real token-endpoint load. */
enum class Expire { NONE, ACCESS_ONLY, ALL }

/** Once-mode: build a client, run one attempt (state already expired by the caller), close it. */
private suspend fun authenticateOnce(deps: ScenarioDeps, row: ClientRow, scopes: List<String>) {
    val card = deps.cardStore.get(row.cardId) ?: return
    val sdk = deps.factory.build(row.clientRef, card, row.resource, scopes)
    try {
        runAttempt(deps, sdk, row, Expire.NONE)
    } finally {
        sdk.closeQuietly()
    }
}

/**
 * Runs a single authenticate attempt against an already-built [sdk], optionally expiring state
 * first, and records the outcome. Success requires the SDK to actually hold an access token
 * afterwards — `authenticate()` can return success without one (e.g. the AS rejects the token
 * request server-side but the SDK swallows it), so we verify rather than trust the return.
 * A stalled attempt is capped by [ScenarioDeps.attemptTimeoutMs] and recorded as a failure.
 */
private suspend fun runAttempt(deps: ScenarioDeps, sdk: ZetaSdkClient, row: ClientRow, expire: Expire) {
    val start = deps.clockMs()
    try {
        withTimeout(deps.attemptTimeoutMs) {
            when (expire) {
                Expire.ALL -> deps.stateExpiry.expireAll(row.clientRef)
                Expire.ACCESS_ONLY -> deps.stateExpiry.expireAccessTokenOnly(row.clientRef)
                Expire.NONE -> Unit
            }
            val status = sdk.status().getOrThrow()
            if (status == SdkStatus.NOT_REGISTERED) {
                sdk.discover().getOrThrow()
                sdk.register().getOrThrow()
            }
            sdk.authenticate().getOrThrow()
            val finalStatus = sdk.status().getOrThrow()
            if (finalStatus == SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN) {
                deps.reporter.record(Attempt("authenticate", deps.clockMs() - start, true, null))
            } else {
                deps.reporter.record(
                    Attempt("authenticate", deps.clockMs() - start, false, "no access token after authenticate (status=$finalStatus)"),
                )
            }
        }
    } catch (e: TimeoutCancellationException) {
        deps.reporter.record(Attempt("authenticate", deps.clockMs() - start, false, "timed out after ${deps.attemptTimeoutMs}ms"))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        deps.reporter.record(Attempt("authenticate", deps.clockMs() - start, false, e.errorLabel()))
    }
}

/**
 * Sustained load: drive the cohort continuously for [durationMs] at [ratePerMin], re-expiring each
 * picked client so every op is a real token-endpoint hit ([expire] picks refresh vs full re-login).
 * SDK clients are built once and reused across ops via [ClientPool] — building one per op would
 * spawn an OkHttp engine per request and exhaust the box under a soak.
 */
fun soak(
    deps: ScenarioDeps,
    resource: String,
    cohort: Int,
    concurrency: Int,
    ratePerMin: Int,
    durationMs: Long,
    expire: Expire,
    scopes: List<String>,
    onTick: (Long, Int, Snapshot) -> Unit,
) {
    val clients = deps.clientStore.cohort(resource, cohort)
    require(clients.isNotEmpty()) { "no registered clients for $resource — run preflight first" }
    log.info { "Soak: ${clients.size} clients, ${ratePerMin}/min for ${durationMs / 1000}s, expire=$expire" }

    ClientPool(deps, resource, scopes).use { pool ->
        runBlocking {
            runContinuous(
                items = clients,
                concurrency = concurrency,
                schedule = Rates.constant(ratePerMin),
                durationMs = durationMs,
                clockMs = deps.clockMs,
                reporter = deps.reporter,
                onTick = onTick,
            ) { row -> pool.get(row)?.let { runAttempt(deps, it, row, expire) } }
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
    cohort: Int,
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
    val clients = deps.clientStore.cohort(resource, cohort)
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
            ) { row -> pool.get(row)?.let { runAttempt(deps, it, row, expire) } }
        }
    }
}

/** Reuses one [ZetaSdkClient] per client_ref across many ops; closes them all on exit. */
private class ClientPool(
    private val deps: ScenarioDeps,
    private val resource: String,
    private val scopes: List<String>,
) : AutoCloseable {
    private val cache = ConcurrentHashMap<String, ZetaSdkClient>()

    fun get(row: ClientRow): ZetaSdkClient? =
        cache.getOrPut(row.clientRef) {
            val card = deps.cardStore.get(row.cardId) ?: return null
            deps.factory.build(row.clientRef, card, resource, scopes.ifEmpty { row.scopes })
        }

    override fun close() = cache.values.forEach { it.closeQuietly() }
}

private fun ZetaSdkClient.closeQuietly() {
    runCatching { runBlocking { close() } }
}

private fun Throwable.errorLabel(): String =
    "${this::class.simpleName}: ${message?.take(120) ?: ""}".trim()
