package de.gematik.zeta.stress.scenario

import de.gematik.zeta.sdk.SdkStatus
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.stress.db.CardStore
import de.gematik.zeta.stress.db.ClientRow
import de.gematik.zeta.stress.db.ClientStore
import de.gematik.zeta.stress.runner.Attempt
import de.gematik.zeta.stress.runner.LoadShape
import de.gematik.zeta.stress.runner.Reporter
import de.gematik.zeta.stress.runner.runLoad
import de.gematik.zeta.stress.sdk.StressSdkClientFactory
import de.gematik.zeta.stress.storage.StateExpiry
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.runBlocking

private val log = KotlinLogging.logger {}

class ScenarioDeps(
    val cardStore: CardStore,
    val clientStore: ClientStore,
    val stateExpiry: StateExpiry,
    val factory: StressSdkClientFactory,
    val reporter: Reporter,
    val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
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
                sdk.discover().getOrThrow()
                sdk.register().getOrThrow()
                val status = sdk.status().getOrThrow()
                deps.clientStore.insert(
                    ClientRow(w.clientRef, w.cardId, resource, scopes, deps.clockMs(), status.name),
                )
                deps.reporter.record(Attempt("register", deps.clockMs() - start, true, null))
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

private suspend fun authenticateOnce(deps: ScenarioDeps, row: ClientRow, scopes: List<String>) {
    val card = deps.cardStore.get(row.cardId) ?: return
    val sdk = deps.factory.build(row.clientRef, card, row.resource, scopes)
    val start = deps.clockMs()
    try {
        val status = sdk.status().getOrThrow()
        if (status == SdkStatus.NOT_REGISTERED) {
            sdk.discover().getOrThrow()
            sdk.register().getOrThrow()
        }
        sdk.authenticate().getOrThrow()
        deps.reporter.record(Attempt("authenticate", deps.clockMs() - start, true, null))
    } catch (e: Throwable) {
        deps.reporter.record(Attempt("authenticate", deps.clockMs() - start, false, e.errorLabel()))
    } finally {
        sdk.closeQuietly()
    }
}

private fun ZetaSdkClient.closeQuietly() = runCatching { runBlocking { close() } }

private fun Throwable.errorLabel(): String =
    "${this::class.simpleName}: ${message?.take(120) ?: ""}".trim()
