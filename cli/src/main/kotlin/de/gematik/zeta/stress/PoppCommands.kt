package de.gematik.zeta.stress

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.counted
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import de.gematik.zeta.cli.client.originOf
import de.gematik.zeta.cli.popp.runKartosPoppFlow
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.stress.db.ClientStore
import de.gematik.zeta.stress.db.Db
import de.gematik.zeta.stress.db.IdentityStore
import de.gematik.zeta.stress.db.PoppRow
import de.gematik.zeta.stress.db.PoppStore
import de.gematik.zeta.stress.identity.PoppImporter
import de.gematik.zeta.stress.identity.PoppJwt
import de.gematik.zeta.stress.runner.Attempt
import de.gematik.zeta.stress.runner.LoadShape
import de.gematik.zeta.stress.runner.Reporter
import de.gematik.zeta.stress.runner.runLoad
import de.gematik.zeta.stress.sdk.HttpSettings
import de.gematik.zeta.stress.sdk.StressSdkClientFactory
import de.gematik.zeta.stress.sdk.applyStressHttp
import de.gematik.zeta.stress.scenario.ProfileYaml
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val log = KotlinLogging.logger {}

/** The `zeta stress popp` command group — obtain, import, and export PoPP tokens for the corpus. */
class StressPoppCommand : CliktCommand(name = "popp") {
    override fun help(context: Context) = "Import, obtain (via kartos), and export PoPP tokens for the corpus."

    override fun run() = Unit
}

class PoppImportCommand : StressBaseCommand(name = "import") {
    private val dir: String by argument(name = "DIR", help = "Directory of PoPP token files (compact JWTs, one per line).")

    override fun help(context: Context) = "Import off-band PoPP tokens and bind them to identities."

    override fun run() {
        applyVerbosity()
        openDb().use { db ->
            val tty = System.console() != null
            val r = PoppImporter(IdentityStore(db), PoppStore(db)).importDir(Path.of(dir)) { done, total, tokens ->
                if (tty) System.err.print("\u001b[2K\rImporting… $done/$total files ($tokens tokens)")
            }
            if (tty) System.err.println()
            echo(
                "Imported ${r.imported} PoPP tokens: ${r.matched} bound to an identity, " +
                    "${r.unmatched} unmatched. Total in DB: ${PoppStore(db).count()}.",
            )
        }
    }
}

class PoppExportCommand : StressBaseCommand(name = "export") {
    private val outDir: String by argument(name = "OUT_DIR", help = "Directory to write <insurant>-<telematik-id>.jwt files into.")

    override fun help(context: Context) = "Export all stored PoPP tokens as <insurant>-<telematik-id>.jwt files."

    override fun run() {
        applyVerbosity()
        openDb().use { db ->
            val out = Path.of(outDir)
            Files.createDirectories(out)
            val rows = PoppStore(db).allRows()
            val tty = System.console() != null
            val used = HashSet<String>()
            rows.forEachIndexed { i, r ->
                val id = r.telematikId ?: r.actorId
                // Filenames are <insurant>-<telematik-id>; disambiguate the rare same-identity /
                // same-patient / different-insurer collision by appending the insurer.
                val base = "${r.patientId}-$id".let { if (used.add(it)) it else "$it-${r.insurerId}" }
                Files.writeString(out.resolve("$base.jwt"), r.token)
                if (tty) System.err.print("\u001b[2K\rExporting… ${i + 1}/${rows.size}")
            }
            if (tty) System.err.println()
            echo("Exported ${rows.size} tokens to ${out.toAbsolutePath()}.")
        }
    }
}

/**
 * `zeta stress popp get <profile.yaml>` — obtain PoPP tokens for the profile's registered roster.
 * For each distinct identity registered for `resource`, drive the kartos Standard flow (via the
 * popp service) against `popp.per-identity` eGK images and store the tokens. Idempotent: tops up to
 * the target per identity unless `--force`. Bounded by `popp.concurrency`.
 */
class PoppGetCommand : CliktCommand(name = "get") {
    private val profileFile: String by argument(name = "PROFILE", help = "YAML run profile with a 'popp:' block.")
    private val dbOverride: String? by option("--db", metavar = "FILE", help = "Override the profile's SQLite state file.")
    private val force: Boolean by option("--force", help = "Re-fetch to the target even for identities already at it.").flag()
    private val verbosity: Int by option("-v", "--verbose", help = "Raise log level (repeatable).").counted()

    override fun help(context: Context) = "Obtain PoPP tokens for the profile's roster via the kartos flow."

    override fun run() {
        applyLogLevel(verbosity)
        val prof = ProfileYaml.load(Path.of(profileFile))
        val resource = prof.resource?.takeIf { it.isNotBlank() } ?: throw UsageError("profile must set 'resource:'")
        val popp = prof.popp ?: throw UsageError("profile needs a 'popp:' block")
        val egkDir = popp.egkDir?.let { Path.of(it) } ?: throw UsageError("popp block needs 'egk-dir'")
        if (!egkDir.isDirectory()) throw UsageError("$egkDir is not a directory")
        val egks = egkDir.listDirectoryEntries("*.xml").sorted()
        if (egks.isEmpty()) throw UsageError("no eGK images (*.xml) in $egkDir")

        val dbPath = dbOverride ?: prof.db ?: "stress.db"
        val poppResource = originOf(popp.serviceUrl)
        val http = HttpSettings(prof.connectTimeoutMs, prof.requestTimeoutMs, prof.insecure, prof.caCerts, prof.aslProd)
        val k = minOf(popp.perIdentity, egks.size)
        if (egks.size < popp.perIdentity) log.warn { "only ${egks.size} eGK image(s) — capping per-identity at $k" }

        Db(Path.of(dbPath), popp.concurrency.coerceIn(4, 128)).use { db ->
            val identityStore = IdentityStore(db)
            val poppStore = PoppStore(db)
            val roster = ClientStore(db).identitiesForResource(resource)
            if (roster.isEmpty()) throw UsageError("no registered clients for $resource — run 'zeta stress preflight' first")

            // Assign K distinct eGKs per identity round-robin from a shared cursor, topping up to K.
            val cursor = AtomicInteger(0)
            val work = roster.mapNotNull { tid ->
                val need = (k - (if (force) 0 else poppStore.countForIdentity(tid))).coerceAtLeast(0)
                if (need == 0) return@mapNotNull null
                val startIdx = cursor.getAndAdd(need)
                Work(tid, (0 until need).map { egks[Math.floorMod(startIdx + it, egks.size)] })
            }
            val totalTokens = work.sumOf { it.egks.size }
            if (totalTokens == 0) {
                echo("Nothing to do — every roster identity already has $k PoPP token(s).")
                return@use
            }

            val factory = StressSdkClientFactory(db, http)
            val clock = { System.nanoTime() / 1_000_000 }
            val reporter = Reporter(clock)
            val progress = liveProgress("popp-get", popp.serviceUrl)
            val start = clock()

            runBlocking(Dispatchers.IO) {
                val ticker = launch {
                    while (isActive) {
                        delay(1000)
                        progress.tickCount(clock() - start, reporter.completed, totalTokens, reporter.window(3000))
                    }
                }
                runLoad(work, popp.concurrency, LoadShape.Burst, clock) { w ->
                    val identity = identityStore.get(w.telematikId) ?: run {
                        log.warn { "identity ${w.telematikId} not in corpus — skipping" }
                        return@runLoad
                    }
                    val sdk = factory.build("popp:${w.telematikId}", identity, poppResource, listOf(popp.scope))
                    val rows = mutableListOf<PoppRow>()
                    try {
                        for (egk in w.egks) {
                            val t0 = clock()
                            try {
                                val token = runKartosPoppFlow(sdk, egk, popp.kartosBin, popp.serviceUrl) { applyStressHttp(http) }
                                val claims = PoppJwt.parse(token)
                                    ?: throw IllegalStateException("popp service returned an unparseable token")
                                if (claims.actorId != w.telematikId) {
                                    log.warn { "token actorId ${claims.actorId} != identity ${w.telematikId}" }
                                }
                                rows += PoppRow(
                                    telematikId = w.telematikId,
                                    actorId = claims.actorId,
                                    patientId = claims.patientId,
                                    insurerId = claims.insurerId,
                                    proofTime = claims.proofTime,
                                    iat = claims.iat,
                                    kid = claims.kid,
                                    token = token,
                                )
                                reporter.record(Attempt("popp", clock() - t0, true, null))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                reporter.record(Attempt("popp", clock() - t0, false, e.message ?: e::class.simpleName))
                            }
                        }
                    } finally {
                        if (rows.isNotEmpty()) poppStore.insertAll(rows)
                        runCatching { sdk.close() }
                    }
                }
                ticker.cancelAndJoin()
                progress.tickCount(clock() - start, reporter.completed, totalTokens, reporter.window(3000))
            }
            progress.close()
            echo(reporter.summary(clock() - start))
            echo("Stored PoPP tokens: ${poppStore.count()} (roster ${roster.size}, target $k each).")
        }
    }

    private data class Work(val telematikId: String, val egks: List<Path>)
}
