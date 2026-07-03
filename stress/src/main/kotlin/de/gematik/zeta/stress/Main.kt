package de.gematik.zeta.stress

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
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
import de.gematik.zeta.stress.runner.LoadShape
import de.gematik.zeta.stress.runner.Reporter
import de.gematik.zeta.stress.scenario.ScenarioDeps
import de.gematik.zeta.stress.scenario.loginStorm
import de.gematik.zeta.stress.scenario.preflight
import de.gematik.zeta.stress.scenario.refreshChurn
import de.gematik.zeta.stress.sdk.HttpSettings
import de.gematik.zeta.stress.sdk.StressSdkClientFactory
import java.nio.file.Path
import org.slf4j.LoggerFactory

fun main(args: Array<String>) {
    StressCommand()
        .subcommands(ImportCardsCommand(), PreflightCommand(), RunCommand(), ReportCommand())
        .main(args)
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
    protected val insecure: Boolean by option("-k", "--insecure", help = "Disable TLS verification.").flag()
    protected val caCerts: List<String> by option("--ca-cert", metavar = "FILE", help = "Extra PEM CA (repeatable).")
        .multiple()
    protected val aslProd: Boolean by option("--asl-prod", help = "Use the TI production ASL root store.").flag()
    protected val verbosity: Int by option("-v", "--verbose", help = "Raise log level (repeatable).").counted()

    protected fun openDb(): Db = Db(Path.of(dbPath))

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
        require(resource.isNotBlank()) { "--resource is required" }
        require(scopes.isNotEmpty()) { "at least one --scope is required" }
        openDb().use { db ->
            val d = deps(db)
            val start = d.clockMs()
            preflight(d, identities, clientsMin, clientsMax, resource, scopes, concurrency, seed)
            echo(d.reporter.summary(d.clockMs() - start))
            echo("Registered clients in DB: ${ClientStore(db).count()}")
        }
    }
}

enum class Scenario { LOGIN_STORM, STEADY, REFRESH_CHURN }

class RunCommand : StressBaseCommand(name = "run") {
    private val scenario: Scenario by option("--scenario", help = "Which load scenario to run.")
        .enum<Scenario> { it.name.lowercase().replace('_', '-') }
        .default(Scenario.LOGIN_STORM)
    private val cohort: Int by option("--cohort", metavar = "N", help = "How many registered clients to drive.")
        .int().default(100)
    private val concurrency: Int by option("--concurrency", metavar = "N", help = "Max in-flight requests.")
        .int().default(100)
    private val rate: Int by option("--rate", metavar = "PER_MIN", help = "Steady-mode admission rate.")
        .int().default(5000)
    private val burst: Boolean by option("--burst", help = "Thundering herd (ignore --rate).").flag()

    override fun help(context: Context) = "Drive a load scenario against ZETA Guard."

    override fun run() {
        applyVerbosity()
        require(resource.isNotBlank()) { "--resource is required" }
        openDb().use { db ->
            val d = deps(db)
            val shape = if (burst) LoadShape.Burst else LoadShape.Steady(rate)
            val start = d.clockMs()
            when (scenario) {
                Scenario.LOGIN_STORM -> loginStorm(d, resource, cohort, concurrency, shape, expire = true, scopes)
                Scenario.STEADY -> loginStorm(d, resource, cohort, concurrency, shape, expire = false, scopes)
                Scenario.REFRESH_CHURN -> refreshChurn(d, resource, cohort, concurrency, shape, scopes)
            }
            echo(d.reporter.summary(d.clockMs() - start))
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
