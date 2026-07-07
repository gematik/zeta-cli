package de.gematik.zeta.stress

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.counted
import com.github.ajalt.clikt.parameters.options.option
import de.gematik.zeta.sdk.SdkStatus
import de.gematik.zeta.stress.db.ClientStore
import de.gematik.zeta.stress.db.Db
import de.gematik.zeta.stress.db.IdentityStore
import de.gematik.zeta.stress.db.PoppStore
import de.gematik.zeta.stress.identity.PoppJwt
import de.gematik.zeta.stress.scenario.ProfileYaml
import de.gematik.zeta.stress.scenario.Scenario
import de.gematik.zeta.stress.sdk.HttpSettings
import de.gematik.zeta.stress.sdk.StressSdkClientFactory
import de.gematik.zeta.stress.sdk.applyStressHttp
import de.gematik.zeta.stress.storage.StateExpiry
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/**
 * `zeta stress verify <profile>` — a single-client proof that the profile's scenario does what it
 * claims. It takes one registered client, wipes the credential state the scenario would expire, runs
 * the same cold chain one attempt runs, and asserts the observable facts a bare status code can't
 * prove:
 *  - **login** (every scenario): SDK state transitions REGISTERED_NO_VALID_TOKENS →
 *    HAS_ACCESS_AND_REFRESH_TOKEN, so nonce → SMC-B sign → token exchange ran (no token exists
 *    otherwise). `refresh-storm` starts from HAS_REFRESH_TOKEN; `register-storm` starts cold
 *    (NOT_REGISTERED) and re-runs the whole DCR + token cycle.
 *  - **VSDM** (`login-and-vsdm-storm` only): the read establishes an ASL session (storage populated)
 *    and returns a FHIR bundle carrying the insurant (KVNR) from the PoPP token that was sent.
 *
 * Exits non-zero if any check fails. Run with `-vvv` to also see every HTTP leg on the wire.
 */
class StressVerifyCommand : CliktCommand(name = "verify") {
    private val profileFile: String by argument(name = "PROFILE", help = "YAML run profile (any scenario).")
    private val dbOverride: String? by option("--db", metavar = "FILE", help = "Override the profile's SQLite state file.")
    private val verbosity: Int by option("-v", "--verbose", help = "Raise log level (repeatable; -vvv shows every HTTP leg).").counted()

    override fun help(context: Context) = "Prove one client runs the profile's scenario (login, and VSDM if configured) end-to-end."

    override fun run() {
        applyLogLevel(verbosity)
        val prof = ProfileYaml.load(Path.of(profileFile))
        val resource = prof.resource?.takeIf { it.isNotBlank() } ?: throw UsageError("profile must set 'resource:'")
        val scenario = prof.scenario
        val vsdm = scenario == Scenario.LOGIN_AND_VSDM_STORM
        val req = if (vsdm) prof.request ?: throw UsageError("login-and-vsdm-storm needs a 'request:' block") else null
        val dbPath = dbOverride ?: prof.db ?: "stress.db"
        val http = HttpSettings(prof.connectTimeoutMs, prof.requestTimeoutMs, prof.insecure, prof.caCerts, prof.aslProd)
        val scopes = prof.scopes ?: emptyList()

        Db(Path.of(dbPath), 4).use { db ->
            val clientStore = ClientStore(db)
            val identityStore = IdentityStore(db)
            val poppStore = PoppStore(db)
            val expiry = StateExpiry(db)

            val roster = clientStore.forResource(resource)
            if (roster.isEmpty()) throw UsageError("no registered clients for $resource — run 'zeta stress preflight' first")

            // VSDM needs a client whose identity holds a PoPP token; login-only can use any client.
            val pick = if (vsdm) {
                roster.firstOrNull { poppStore.forIdentity(it.telematikId).isNotEmpty() }
                    ?: throw UsageError("no roster identity has a PoPP token — run 'zeta stress popp get' first")
            } else {
                roster.first()
            }
            val identity = identityStore.get(pick.telematikId) ?: throw UsageError("identity ${pick.telematikId} not in corpus")
            val token = if (vsdm) poppStore.forIdentity(pick.telematikId).first() else null
            val kvnr = token?.let { PoppJwt.parse(it)?.patientId ?: throw UsageError("stored PoPP token for ${pick.telematikId} is unparseable") }

            val sdk = StressSdkClientFactory(db, http).build(pick.clientRef, identity, resource, scopes.ifEmpty { pick.scopes })
            val checks = mutableListOf<Check>()
            val startedAt = System.nanoTime()
            runBlocking {
                try {
                    val client = sdk.httpClient { applyStressHttp(http) }

                    // Expire the same state this scenario would, then assert the cold-start precondition.
                    val expectedPre = when (scenario) {
                        Scenario.REFRESH_STORM -> {
                            expiry.expireAccessTokenOnly(pick.clientRef)
                            SdkStatus.HAS_REFRESH_TOKEN
                        }
                        Scenario.REGISTER_STORM -> {
                            expiry.expireEverything(pick.clientRef)
                            SdkStatus.NOT_REGISTERED
                        }
                        else -> {
                            expiry.expireAll(pick.clientRef)
                            SdkStatus.REGISTERED_NO_VALID_TOKENS
                        }
                    }
                    val before = sdk.status().getOrThrow()
                    checks += Check("login: pre-state is $expectedPre", before == expectedPre, "$before")
                    if (scenario != Scenario.REFRESH_STORM) {
                        val atBefore = expiry.countKeys(pick.clientRef, "at:%")
                        val aslBefore = expiry.countKeys(pick.clientRef, "asl_%")
                        checks += Check("login: storage cleared (no tokens, no ASL)", atBefore == 0 && aslBefore == 0, "at=$atBefore asl=$aslBefore")
                    }

                    if (vsdm) {
                        verifyVsdm(client, sdk, expiry, req!!, token!!, kvnr!!, pick.clientRef, checks)
                    } else {
                        verifyLogin(sdk, checks)
                    }
                } finally {
                    runCatching { sdk.close() }
                }
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            val who = "client ${pick.clientRef} (identity ${pick.telematikId}${kvnr?.let { ", insurant $it" } ?: ""})"
            echo("verify [${scenario.name.lowercase().replace('_', '-')}] — $who")
            echo(if (vsdm) "resource $resource → ${req!!.url}" else "resource $resource")
            echo("")
            checks.forEach { c -> echo("  ${if (c.ok) "✓" else "✗"} ${c.label}${if (c.ok) "" else "   → ${c.detail}"}") }
            echo("")
            if (checks.all { it.ok }) {
                echo("PASS — ${checks.size}/${checks.size} checks passed in ${elapsedMs} ms.")
            } else {
                throw CliktError("FAIL — ${checks.count { !it.ok }} of ${checks.size} checks failed (${elapsedMs} ms).")
            }
        }
    }

    private suspend fun verifyLogin(sdk: de.gematik.zeta.sdk.ZetaSdkClient, checks: MutableList<Check>) {
        try {
            if (sdk.status().getOrThrow() == SdkStatus.NOT_REGISTERED) {
                sdk.discover().getOrThrow()
                sdk.register().getOrThrow()
            }
            sdk.authenticate().getOrThrow()
            val after = sdk.status().getOrThrow()
            checks += Check("login: access + refresh minted (nonce → sign → token exchange)", after == SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN, "$after")
        } catch (e: Throwable) {
            checks += Check("login: authenticate completed", false, e.message ?: e::class.simpleName ?: "unknown error")
        }
    }

    private suspend fun verifyVsdm(
        client: de.gematik.zeta.sdk.network.http.client.ZetaHttpClient,
        sdk: de.gematik.zeta.sdk.ZetaSdkClient,
        expiry: StateExpiry,
        req: de.gematik.zeta.stress.scenario.VsdmRequest,
        token: String,
        kvnr: String,
        clientRef: String,
        checks: MutableList<Check>,
    ) {
        try {
            val resp = client.request(req.url) {
                method = HttpMethod.parse(req.method)
                req.headers.forEach { (k, v) -> header(k, v) }
                header("PoPP", token)
            }
            val code = resp.status.value
            val body = resp.bodyAsText()

            val after = sdk.status().getOrThrow()
            checks += Check("login: access + refresh minted (nonce → sign → token exchange)", after == SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN, "$after")
            val aslAfter = expiry.countKeys(clientRef, "asl_%")
            checks += Check("vsdm: ASL session established (storage populated)", aslAfter > 0, "asl rows=$aslAfter")
            checks += Check("vsdm: HTTP $code accepted", code in req.expectStatus, "expected ${req.expectStatus}")
            checks += Check(
                "popp: FHIR body carries insurant $kvnr",
                code == 200 && kvnr in body,
                if (code == 200) "not found in ${body.length}-byte body" else "no body (status $code)",
            )
        } catch (e: Throwable) {
            checks += Check("vsdm: request completed", false, e.message ?: e::class.simpleName ?: "unknown error")
        }
    }

    private data class Check(val label: String, val ok: Boolean, val detail: String)
}
