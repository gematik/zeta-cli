package de.gematik.zeta.stress

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.counted
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import de.gematik.zeta.stress.identity.IdentityImporter
import de.gematik.zeta.stress.db.IdentityStore
import de.gematik.zeta.stress.db.ClientStore
import de.gematik.zeta.stress.db.Db
import de.gematik.zeta.stress.db.PoppStore
import de.gematik.zeta.stress.runner.LiveView
import de.gematik.zeta.stress.runner.PlainProgress
import de.gematik.zeta.stress.runner.Progress
import de.gematik.zeta.stress.runner.Reporter
import de.gematik.zeta.stress.runner.Snapshot
import de.gematik.zeta.stress.scenario.Expire
import de.gematik.zeta.stress.scenario.PoppPicker
import de.gematik.zeta.stress.scenario.Scenario
import de.gematik.zeta.stress.scenario.ScenarioDeps
import de.gematik.zeta.stress.report.PhaseDef
import de.gematik.zeta.stress.report.PhaseSpan
import de.gematik.zeta.stress.report.ReportWriter
import de.gematik.zeta.stress.report.RunMeta
import de.gematik.zeta.stress.scenario.ProfileYaml
import de.gematik.zeta.stress.scenario.preflight
import de.gematik.zeta.stress.scenario.profile
import de.gematik.zeta.stress.scenario.ramp
import de.gematik.zeta.stress.storage.StateExpiry
import de.gematik.zeta.stress.sdk.HttpSettings
import de.gematik.zeta.stress.sdk.StressSdkClientFactory
import java.nio.file.Path
import org.slf4j.LoggerFactory

/** The `zeta stress` command group — a plain container for the load-test subcommands. */
class StressCommand : CliktCommand(name = "stress") {
    override fun help(context: Context) = "Load-test ZETA Guard with a fleet of SMC-B-backed clients."

    override fun run() = Unit
}

/** Wires the `zeta stress` group with its subcommands, for registration under the root `zeta`. */
fun stressCommand(): CliktCommand =
    StressCommand().subcommands(
        ImportIdentitiesCommand(),
        StressPoppCommand().subcommands(PoppImportCommand(), PoppGetCommand(), PoppExportCommand()),
        PreflightCommand(),
        RunCommand(),
        StressVerifyCommand(),
        StressDbCommand().subcommands(DbInfoCommand()),
    )

/** Raise the root log level per `-v` count (INFO/DEBUG/TRACE); no-op at 0. */
internal fun applyLogLevel(verbosity: Int) {
    val level = when (verbosity) {
        0 -> return
        1 -> Level.INFO
        2 -> Level.DEBUG
        else -> Level.TRACE
    }
    (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).level = level
}

/** k9s-style live panel on a TTY; a plain per-second line when piped, so logs/reports stay clean. */
internal fun liveProgress(scenario: String, target: String): Progress =
    if (System.console() != null) LiveView("zeta-stress", scenario, target, colorize = true) else PlainProgress()

/** Shared options for the corpus/roster subcommands: just the DB file and verbosity. */
abstract class StressBaseCommand(name: String) : CliktCommand(name = name) {
    protected val dbPath: String by option("--db", metavar = "FILE", help = "SQLite state file.")
        .default("stress.db")
    protected val verbosity: Int by option("-v", "--verbose", help = "Raise log level (repeatable).").counted()

    protected fun openDb(poolSize: Int = 8): Db = Db(Path.of(dbPath), poolSize.coerceIn(4, 128))
    protected fun applyVerbosity() = applyLogLevel(verbosity)
}

class ImportIdentitiesCommand : StressBaseCommand(name = "import-identities") {
    private val dir: String by argument(name = "DIR", help = "Directory of *.tar.gz SMC-B bundles.")

    override fun help(context: Context) = "Import the SMC-B identity corpus into the DB."

    override fun run() {
        applyVerbosity()
        openDb().use { db ->
            val tty = System.console() != null
            val n = IdentityImporter(IdentityStore(db)).importDir(Path.of(dir)) { done, total, identities ->
                if (tty) System.err.print("\u001b[2K\rImporting… $done/$total bundles ($identities identities)")
            }
            if (tty) System.err.println()
            echo("Imported $n identities (total in DB: ${IdentityStore(db).count()}).")
        }
    }
}

/**
 * `zeta stress preflight <profile.yaml>` — register the run's client population (DCR) from the same
 * profile the run uses, so `run` has clients to drive. Reads resource / scope / cohort / concurrency
 * / TLS from the profile and registers `cohort.institutions` SMC-B identities, each with a random
 * `cohort.clients-per-institution` OAuth clients. Idempotent: re-running tops up per identity to its
 * (seeded) target, skipping those already there.
 */
class PreflightCommand : CliktCommand(name = "preflight") {
    private val profileFile: String by argument(name = "PROFILE", help = "YAML run profile — resource, scope, cohort, …")
    private val dbOverride: String? by option("--db", metavar = "FILE", help = "Override the profile's SQLite state file.")
    private val verbosity: Int by option("-v", "--verbose", help = "Raise log level (repeatable).").counted()

    override fun help(context: Context) = "Register the run's client population (DCR) from a profile."

    override fun run() {
        applyLogLevel(verbosity)
        val prof = ProfileYaml.load(Path.of(profileFile))
        val resource = prof.resource?.takeIf { it.isNotBlank() } ?: throw UsageError("profile must set 'resource:'")
        val scopes = prof.scopes ?: emptyList()
        if (scopes.isEmpty()) throw UsageError("profile must set 'scope:'")
        val dbPath = dbOverride ?: prof.db ?: "stress.db"
        val cohort = prof.cohort
        val http = HttpSettings(prof.connectTimeoutMs, prof.requestTimeoutMs, prof.insecure, prof.caCerts, prof.aslProd)

        Db(Path.of(dbPath), prof.concurrency.coerceIn(4, 128)).use { db ->
            val d = ScenarioDeps(
                identityStore = IdentityStore(db),
                clientStore = ClientStore(db),
                stateExpiry = StateExpiry(db),
                factory = StressSdkClientFactory(db, http),
                http = http,
                reporter = Reporter(),
                attemptTimeoutMs = prof.attemptTimeoutMs,
            )
            val start = d.clockMs()
            preflight(
                d, cohort.institutions,
                cohort.clientsPerInstitution.first, cohort.clientsPerInstitution.last,
                resource, scopes, prof.concurrency, cohort.seed, liveProgress("preflight", resource),
            )
            echo(d.reporter.summary(d.clockMs() - start))
            echo("Registered clients in DB: ${ClientStore(db).count()}")
        }
    }
}

/**
 * `zeta stress run <profile.yaml>` — everything about the run lives in the YAML profile (see
 * [ProfileYaml]); the only flags are `-v` and `--db` (an optional override of the profile's `db`).
 * The scenario picks the state to expire and whether each attempt is a bare login or a VSDM read.
 */
class RunCommand : CliktCommand(name = "run") {
    private val profileFile: String by argument(name = "PROFILE", help = "YAML run profile — all run settings live here.")
    private val dbOverride: String? by option("--db", metavar = "FILE", help = "Override the profile's SQLite state file.")
    private val verbosity: Int by option("-v", "--verbose", help = "Raise log level (repeatable).").counted()

    override fun help(context: Context) = "Drive a load scenario against ZETA Guard from a YAML profile."

    override fun run() {
        applyLogLevel(verbosity)
        val prof = ProfileYaml.load(Path.of(profileFile))

        val resource = prof.resource?.takeIf { it.isNotBlank() } ?: throw UsageError("profile must set 'resource:'")
        val scopes = prof.scopes ?: emptyList()
        val dbPath = dbOverride ?: prof.db ?: "stress.db"
        val (expire, request) = when (prof.scenario) {
            Scenario.LOGIN_STORM -> Expire.ALL to null
            Scenario.LOGIN_AND_VSDM_STORM ->
                Expire.ALL to (prof.request ?: throw UsageError("scenario login-and-vsdm-storm needs a 'request:' block"))
            Scenario.REFRESH_CHURN -> Expire.ACCESS_ONLY to null
        }

        val startedAt = java.time.LocalDateTime.now()
        val host = runCatching { java.net.URI(resource).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: resource
        val scenarioLabel = prof.scenario.name.lowercase().replace('_', '-')
        var phaseDefs: List<PhaseDef> = emptyList()
        var phaseSpans: List<PhaseSpan> = emptyList()
        var plannedMs = 0L

        val http = HttpSettings(
            connectTimeoutMs = prof.connectTimeoutMs,
            requestTimeoutMs = prof.requestTimeoutMs,
            insecure = prof.insecure,
            caCertFiles = prof.caCerts,
            aslProdEnvironment = prof.aslProd,
        )
        val progress = liveProgress(scenarioLabel, resource)
        val tick: (Long, Int, Snapshot) -> Unit = { elapsedMs, target, w ->
            if (prof.hasWaveform) prof.phaseAt(elapsedMs).let { progress.phase(it.current, it.next, it.remainingSec) }
            progress.tick(elapsedMs, target, w)
        }

        Db(Path.of(dbPath), prof.concurrency.coerceIn(4, 128)).use { db ->
            val clients = ClientStore(db).forResource(resource)
            if (clients.isEmpty()) {
                throw UsageError("no registered clients for $resource — run 'zeta stress preflight' first")
            }
            val d = ScenarioDeps(
                identityStore = IdentityStore(db),
                clientStore = ClientStore(db),
                stateExpiry = StateExpiry(db),
                factory = StressSdkClientFactory(db, http),
                http = http,
                reporter = Reporter(),
                attemptTimeoutMs = prof.attemptTimeoutMs,
                request = request,
                poppPicker = if (request?.popp == true) PoppPicker(PoppStore(db)) else null,
            )
            val start = d.clockMs()
            val rampSpec = prof.ramp
            if (rampSpec != null) {
                plannedMs = rampSpec.durationMs ?: run {
                    val steps = ((rampSpec.maxPerMin - rampSpec.startPerMin).coerceAtLeast(0) / rampSpec.stepPerMin.coerceAtLeast(1)) + 2
                    steps * rampSpec.stepEverySec * 1000
                }
                val result = ramp(
                    d, resource, clients, prof.concurrency,
                    startPerMin = rampSpec.startPerMin, stepPerMin = rampSpec.stepPerMin, stepEverySec = rampSpec.stepEverySec,
                    maxPerMin = rampSpec.maxPerMin, durationMs = plannedMs,
                    maxFailFraction = rampSpec.maxFailPct / 100.0, maxP99Ms = rampSpec.maxP99Ms,
                    expire = expire, scopes = scopes, onTick = tick,
                )
                progress.close()
                echo(d.reporter.summary(d.clockMs() - start))
                echo(
                    if (result.stoppedEarly) "Breaking point reached — peak healthy rate ≈ ${result.peakHealthyPerMin} req/min"
                    else "Ramp finished without breaking — peak healthy rate ≈ ${result.peakHealthyPerMin} req/min",
                )
            } else {
                if (!prof.hasWaveform) throw UsageError("profile needs a 'cycle:' waveform or a 'ramp:' block")
                val totalMs = prof.durationMs ?: (prof.warmupMs + prof.cycleMs)
                plannedMs = totalMs
                phaseDefs = prof.allPhases().map { PhaseDef(it.name, it.spec, it.durationMs) }
                val tl = prof.timeline(totalMs)
                phaseSpans = tl.mapIndexed { i, (name, startMs) ->
                    val endMs = tl.getOrNull(i + 1)?.second ?: totalMs
                    PhaseSpan(name, startMs / 1000.0, endMs / 1000.0)
                }
                profile(d, resource, clients, prof.concurrency, prof.schedule(), totalMs, expire, scopes, tick)
                progress.close()
                echo(d.reporter.summary(d.clockMs() - start))
            }

            val reportDir = ReportWriter.write(
                RunMeta(
                    resource = resource,
                    host = host,
                    scenario = scenarioLabel,
                    cohort = clients.size,
                    concurrency = prof.concurrency,
                    insecure = prof.insecure,
                    startedAt = startedAt,
                    plannedMs = plannedMs,
                    wallMs = d.clockMs() - start,
                    phaseDefs = phaseDefs,
                    phaseSpans = phaseSpans,
                ),
                d.reporter.rows(),
            )
            echo("Report: ${reportDir.toAbsolutePath()}/report.html")
        }
    }
}

/** The `zeta stress db` command group — a container for DB inspection / maintenance subcommands. */
class StressDbCommand : CliktCommand(name = "db") {
    override fun help(context: Context) = "Inspect and manage the stress state DB."

    override fun run() = Unit
}

class DbInfoCommand : StressBaseCommand(name = "info") {
    override fun help(context: Context) = "Show corpus + roster totals, broken down per endpoint."

    override fun run() {
        applyVerbosity()
        openDb().use { db ->
            val clients = ClientStore(db)
            echo("identities  : ${IdentityStore(db).count()}")
            echo("clients     : ${clients.count()}")
            echo("popp tokens : ${PoppStore(db).count()}")
            val perResource = clients.perResource()
            if (perResource.isNotEmpty()) {
                echo("")
                echo("per endpoint:")
                val w = perResource.maxOf { it.resource.length }
                for (r in perResource) {
                    echo("  ${r.resource.padEnd(w)}   clients ${r.clients}   institutions ${r.identities}")
                }
            }
        }
    }
}
