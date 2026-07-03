package de.gematik.zeta.stress

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.counted
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import de.gematik.zeta.stress.card.CardImporter
import de.gematik.zeta.stress.db.CardStore
import de.gematik.zeta.stress.db.ClientStore
import de.gematik.zeta.stress.db.Db
import de.gematik.zeta.stress.db.ResultStore
import de.gematik.zeta.stress.runner.LiveView
import de.gematik.zeta.stress.runner.LoadShape
import de.gematik.zeta.stress.runner.PlainProgress
import de.gematik.zeta.stress.runner.Progress
import de.gematik.zeta.stress.runner.Reporter
import de.gematik.zeta.stress.runner.Snapshot
import de.gematik.zeta.stress.scenario.Expire
import de.gematik.zeta.stress.scenario.ScenarioDeps
import de.gematik.zeta.stress.scenario.loginStorm
import de.gematik.zeta.stress.scenario.preflight
import de.gematik.zeta.stress.scenario.ramp
import de.gematik.zeta.stress.scenario.refreshChurn
import de.gematik.zeta.stress.scenario.soak
import de.gematik.zeta.stress.sdk.HttpSettings
import de.gematik.zeta.stress.sdk.StressSdkClientFactory
import java.nio.file.Path
import org.slf4j.LoggerFactory

fun main(args: Array<String>) {
    try {
        StressCommand()
            .subcommands(ImportCardsCommand(), PreflightCommand(), RunCommand(), ReportCommand())
            .main(args)
    } catch (e: Exception) {
        // Clikt renders its own CliktError/UsageError (clean message + exit code) inside main().
        // Anything else — a failed precondition, an SDK/DB error — would otherwise print a raw
        // stack trace; surface it as a one-line message instead.
        System.err.println("Error: ${e.message ?: e::class.simpleName}")
        kotlin.system.exitProcess(1)
    }
    // The SDK/CLI run on Ktor's OkHttp engine, whose non-daemon dispatcher threads keep the JVM
    // alive ~60s after work finishes. Exit promptly instead of hanging.
    kotlin.system.exitProcess(0)
}

/** Root command — holds the options shared by every subcommand. */
class StressCommand : CliktCommand(name = "zeta-stress") {
    init {
        // Options declared here are inherited by subcommands via the shared context.
    }

    override fun help(context: Context) = "Load-test ZETA Guard with a fleet of SMC-B-backed clients."

    override fun run() = Unit
}

/** Common options + helpers. Each concrete subcommand declares these itself (Clikt inheritance). */
abstract class StressBaseCommand(name: String) : CliktCommand(name = name) {
    protected val dbPath: String by option("--db", metavar = "FILE", help = "SQLite state file.")
        .default("stress.db")
    protected val resource: String by option("--resource", metavar = "URL", help = "ZETA-protected resource origin.")
        .default("")
    protected val scopes: List<String> by option("-s", "--scope", metavar = "NAME", help = "OAuth scope (repeatable).")
        .multiple()
    protected val connectTimeout: Long? by option("--connect-timeout", metavar = "SECONDS").long()
    protected val requestTimeout: Long? by option("--request-timeout", metavar = "SECONDS").long()
    protected val attemptTimeout: Long by option(
        "--attempt-timeout", metavar = "SECONDS",
        help = "Wall-clock cap per client attempt; a stalled attempt is recorded as a failure.",
    ).long().default(120)
    protected val insecure: Boolean by option("-k", "--insecure", help = "Disable TLS verification.").flag()
    protected val caCerts: List<String> by option("--ca-cert", metavar = "FILE", help = "Extra PEM CA (repeatable).")
        .multiple()
    protected val aslProd: Boolean by option("--asl-prod", help = "Use the TI production ASL root store.").flag()
    protected val verbosity: Int by option("-v", "--verbose", help = "Raise log level (repeatable).").counted()

    protected fun openDb(poolSize: Int = 8): Db = Db(Path.of(dbPath), poolSize.coerceIn(4, 128))

    /** k9s-style live panel on a TTY; a plain per-second line when piped, so logs/CSV stay clean. */
    protected fun progress(scenario: String): Progress =
        if (System.console() != null) LiveView("zeta-stress", scenario, resource, colorize = true) else PlainProgress()

    protected fun httpSettings() = HttpSettings(
        connectTimeoutMs = connectTimeout?.let { it * 1000 },
        requestTimeoutMs = requestTimeout?.let { it * 1000 },
        insecure = insecure,
        caCertFiles = caCerts,
        aslProdEnvironment = aslProd,
    )

    protected fun deps(db: Db) = ScenarioDeps(
        cardStore = CardStore(db),
        clientStore = ClientStore(db),
        stateExpiry = de.gematik.zeta.stress.storage.StateExpiry(db),
        factory = StressSdkClientFactory(db, httpSettings()),
        reporter = Reporter(),
        attemptTimeoutMs = attemptTimeout * 1000,
    )

    protected fun applyVerbosity() {
        val level = when (verbosity) {
            0 -> return
            1 -> Level.INFO
            2 -> Level.DEBUG
            else -> Level.TRACE
        }
        (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).level = level
    }
}

class ImportCardsCommand : StressBaseCommand(name = "import-cards") {
    private val dir: String by argument(name = "DIR", help = "Directory of *.tar.gz SMC-B bundles.")

    override fun help(context: Context) = "Import the SMC-B card corpus into the DB."

    override fun run() {
        applyVerbosity()
        openDb().use { db ->
            val n = CardImporter(CardStore(db)).importDir(Path.of(dir))
            echo("Imported $n cards (total in DB: ${CardStore(db).count()}).")
        }
    }
}

class PreflightCommand : StressBaseCommand(name = "preflight") {
    private val identities: Int by option("--identities", metavar = "N", help = "Cards to register clients for.")
        .int().default(100)
    private val clientsMin: Int by option("--clients-min", metavar = "N").int().default(0)
    private val clientsMax: Int by option("--clients-max", metavar = "N").int().default(10)
    private val concurrency: Int by option("--concurrency", metavar = "N").int().default(50)
    private val seed: Long? by option("--seed", metavar = "N", help = "RNG seed for reproducible fan-out.").long()

    override fun help(context: Context) =
        "Register a fuzzy 0-N OAuth clients per SMC-B identity (DCR preflight)."

    override fun run() {
        applyVerbosity()
        if (resource.isBlank()) throw UsageError("--resource is required")
        if (scopes.isEmpty()) throw UsageError("at least one --scope is required")
        openDb(concurrency).use { db ->
            val d = deps(db)
            val start = d.clockMs()
            preflight(d, identities, clientsMin, clientsMax, resource, scopes, concurrency, seed, progress("preflight"))
            echo(d.reporter.summary(d.clockMs() - start))
            echo("Registered clients in DB: ${ClientStore(db).count()}")
        }
    }
}

enum class Scenario { LOGIN_STORM, REFRESH_CHURN }

class RunCommand : StressBaseCommand(name = "run") {
    private val scenario: Scenario by option("--scenario", help = "What each op does: login-storm (full re-login) or refresh-churn (refresh only).")
        .enum<Scenario> { it.name.lowercase().replace('_', '-') }
        .default(Scenario.LOGIN_STORM)
    private val cohort: Int by option("--cohort", metavar = "N", help = "How many registered clients to drive.")
        .int().default(100)
    private val concurrency: Int by option("--concurrency", metavar = "N", help = "Max in-flight requests.")
        .int().default(100)
    private val rate: Int by option("--rate", metavar = "PER_MIN", help = "Target admission rate (soak) / ramp ceiling.")
        .int().default(5000)
    private val burst: Boolean by option("--burst", help = "One-shot thundering herd (ignore --rate/--duration).").flag()
    private val duration: Long by option("--duration", metavar = "SECONDS", help = "Sustain the load for this long (soak).")
        .long().default(0)

    private val doRamp: Boolean by option("--ramp", help = "Ramp the rate up until the AS breaks; report the peak healthy rate.").flag()
    private val rampStart: Int by option("--ramp-start", metavar = "PER_MIN").int().default(500)
    private val rampStep: Int by option("--ramp-step", metavar = "PER_MIN").int().default(500)
    private val rampStepInterval: Long by option("--ramp-step-interval", metavar = "SECONDS").long().default(20)
    private val maxFailPct: Int by option("--max-fail-pct", help = "Ramp stops when window failure % exceeds this.").int().default(10)
    private val maxP99: Long by option("--max-p99", metavar = "MS", help = "Ramp stops when window p99 exceeds this.").long().default(5000)

    private val csvOut: String? by option("--csv", metavar = "FILE", help = "Export per-attempt results as CSV.")
    private val jsonOut: String? by option("--json-out", metavar = "FILE", help = "Export per-attempt results as JSON.")

    override fun help(context: Context) = "Drive a load scenario against ZETA Guard."

    override fun run() {
        applyVerbosity()
        if (resource.isBlank()) throw UsageError("--resource is required")
        val expire = if (scenario == Scenario.REFRESH_CHURN) Expire.ACCESS_ONLY else Expire.ALL
        val progress = progress(scenario.name.lowercase().replace('_', '-'))
        val tick: (Long, Int, Snapshot) -> Unit = { elapsedMs, target, w -> progress.tick(elapsedMs, target, w) }

        openDb(concurrency).use { db ->
            val d = deps(db)
            val start = d.clockMs()
            when {
                doRamp -> {
                    // Default the cap to enough time to climb from start to the ceiling (plus a
                    // little tail); an explicit --duration overrides it.
                    val stepsToCeiling = ((rate - rampStart).coerceAtLeast(0) / rampStep.coerceAtLeast(1)) + 2
                    val rampDurationSec = if (duration > 0) duration else stepsToCeiling * rampStepInterval
                    val result = ramp(
                        d, resource, cohort, concurrency,
                        startPerMin = rampStart, stepPerMin = rampStep, stepEverySec = rampStepInterval,
                        maxPerMin = rate, durationMs = rampDurationSec * 1000,
                        maxFailFraction = maxFailPct / 100.0, maxP99Ms = maxP99,
                        expire = expire, scopes = scopes, onTick = tick,
                    )
                    progress.close()
                    echo(d.reporter.summary(d.clockMs() - start))
                    echo(
                        if (result.stoppedEarly) "Breaking point reached — peak healthy rate ≈ ${result.peakHealthyPerMin} req/min"
                        else "Ramp finished without breaking — peak healthy rate ≈ ${result.peakHealthyPerMin} req/min",
                    )
                }

                duration > 0 -> {
                    soak(d, resource, cohort, concurrency, rate, duration * 1000, expire, scopes, tick)
                    progress.close()
                    echo(d.reporter.summary(d.clockMs() - start))
                }

                else -> {
                    val shape = if (burst) LoadShape.Burst else LoadShape.Steady(rate)
                    when (scenario) {
                        Scenario.LOGIN_STORM -> loginStorm(d, resource, cohort, concurrency, shape, expire = true, scopes)
                        Scenario.REFRESH_CHURN -> refreshChurn(d, resource, cohort, concurrency, shape, scopes)
                    }
                    echo(d.reporter.summary(d.clockMs() - start))
                }
            }

            if (csvOut != null || jsonOut != null) {
                val rows = d.reporter.rows()
                ResultStore(db).insertAll(rows)
                csvOut?.let { ResultStore.exportCsv(rows, Path.of(it)); echo("Wrote ${rows.size} results to $it") }
                jsonOut?.let { ResultStore.exportJson(rows, Path.of(it)); echo("Wrote ${rows.size} results to $it") }
            }
        }
    }
}

class ReportCommand : StressBaseCommand(name = "report") {
    override fun help(context: Context) = "Show DB corpus + roster counts."

    override fun run() {
        openDb().use { db ->
            echo("cards   : ${CardStore(db).count()}")
            echo("clients : ${ClientStore(db).count()}")
        }
    }
}
