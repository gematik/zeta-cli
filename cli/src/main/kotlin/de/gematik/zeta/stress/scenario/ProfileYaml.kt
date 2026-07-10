package de.gematik.zeta.stress.scenario

import de.gematik.zeta.stress.runner.Phase
import de.gematik.zeta.stress.runner.RateSchedule
import de.gematik.zeta.stress.runner.Rates
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/** The active waveform phase at a point in time, for the live panel. */
data class PhaseInfo(val current: String, val next: String, val remainingSec: Long)

/**
 * A parsed `zeta stress run <profile.yaml>` — the single source of truth for a run. Every setting
 * lives here (with a default), so the CLI only needs the profile path plus `-v` / `--db`. Holds the
 * fleet + connection settings, the scenario (and its VSDM request), and either a rate waveform
 * ([warmup] + [cycle]) or a [ramp]. [schedule] stitches the waveform via [Rates.profile].
 */
class RunProfile(
    val db: String?,
    val title: String?,
    val resource: String?,
    val scopes: List<String>?,
    val cohort: CohortSpec,
    val concurrency: Int,
    val insecure: Boolean,
    val aslProd: Boolean,
    val caCerts: List<String>,
    val connectTimeoutMs: Long?,
    val requestTimeoutMs: Long?,
    val attemptTimeoutMs: Long,
    val maxLiveClients: Int?,
    val abortOnFailFraction: Double?,
    val randomClients: Boolean,
    val durationMs: Long?,
    val scenario: Scenario,
    val request: VsdmRequest?,
    val ramp: RampSpec?,
    val popp: PoppSpec?,
    private val warmup: Phase?,
    private val cycle: List<Phase>,
) {
    val warmupMs: Long = warmup?.durationMs ?: 0L
    val cycleMs: Long = cycle.sumOf { it.durationMs }
    val hasWaveform: Boolean = cycle.isNotEmpty()

    fun schedule(): RateSchedule = Rates.profile(warmup, cycle)

    /** Distinct phases in declaration order (warm-up first if present), for the report's phase table. */
    fun allPhases(): List<Phase> = (if (warmup != null) listOf(warmup) else emptyList()) + cycle

    /** Phase-transition markers (name, startMs) across [totalMs], for report annotation. */
    fun timeline(totalMs: Long): List<Pair<String, Long>> {
        val out = mutableListOf<Pair<String, Long>>()
        var t = 0L
        if (warmup != null) {
            out += warmup.name to 0L
            t = warmupMs
        }
        while (t < totalMs && cycleMs > 0) {
            for (p in cycle) {
                if (t >= totalMs) break
                out += p.name to t
                t += p.durationMs
            }
        }
        return out
    }

    /** Which phase is active at [elapsedMs], what follows it, and seconds left in it. */
    fun phaseAt(elapsedMs: Long): PhaseInfo {
        val firstCycle = cycle.first().name
        if (warmup != null && elapsedMs < warmupMs) {
            return PhaseInfo(warmup.name, firstCycle, ceilSec(warmupMs - elapsedMs))
        }
        var pos = (elapsedMs - warmupMs) % cycleMs
        var i = 0
        while (pos >= cycle[i].durationMs) { pos -= cycle[i].durationMs; i++ }
        val next = cycle[(i + 1) % cycle.size].name
        return PhaseInfo(cycle[i].name, next, ceilSec(cycle[i].durationMs - pos))
    }

    private fun ceilSec(ms: Long): Long = (ms + 999) / 1000
}

/**
 * Loads a YAML run profile. Every key is optional bar the ones a scenario needs at run time
 * (`resource`, and `request.url` for `login-and-vsdm-storm`):
 *
 * ```yaml
 * resource: https://vsdm-dev.tk.de
 * scope: vsdservice               # or scopes: [a, b]
 * cohort:                         # or a bare number for N institutions, one client each
 *   institutions: 2000            # N SMC-B identities
 *   clients-per-institution: 1..8 # each registers a random 1..8 OAuth clients
 * concurrency: 200
 * insecure: true
 * duration: 5m                    # total run time (ms/s/m/h; bare = seconds)
 * scenario: login-and-vsdm-storm  # login-storm | login-and-vsdm-storm | refresh-storm | register-storm
 *
 * request:                        # required for login-and-vsdm-storm
 *   url: https://vsdm-dev.tk.de/vsdservice/v1/vsdmbundle?profileVersion=1.0
 *   headers:
 *     Accept: application/fhir+json
 *     If-None-Match: '"0000000000000000000000000000000000000000000000000000000000000000"'
 *   popp: true                    # attach a cached PoPP token as the PoPP header
 *
 * warmup:                         # optional; the cycle repeats until `duration`
 *   name: warmup
 *   rate: 300
 *   duration: 30s
 * cycle:
 *   - name: burst
 *     rate: 5000
 *     duration: 1m
 *   - name: calm
 *     rate: 300
 *     duration: 1m
 *
 * # ...or, instead of warmup/cycle, a breaking-point ramp:
 * # ramp: { start: 500, step: 500, step-interval: 20, ceiling: 5000, max-fail-pct: 10, max-p99: 5000 }
 * ```
 *
 * A phase with `peak` is a spike phase (needs `base`, `peak`, `probability`); otherwise a constant
 * hold (needs `rate`). Durations accept `ms`/`s`/`m`/`h` (bare = seconds).
 */
object ProfileYaml {
    fun load(path: Path): RunProfile {
        val root = Files.newBufferedReader(path).use { Yaml().load<Map<String, Any?>>(it) }
            ?: error("empty profile: $path")

        val warmup = (root["warmup"] as? Map<*, *>)?.let { phase(it, default = "warmup") }
        val cycle = (root["cycle"] as? List<*>)?.mapIndexed { i, e ->
            phase(e as? Map<*, *> ?: error("cycle entry ${i + 1} must be a mapping"), default = "phase${i + 1}")
        } ?: emptyList()

        return RunProfile(
            db = root["db"]?.toString(),
            title = root["title"]?.toString()?.takeIf { it.isNotBlank() },
            resource = root["resource"]?.toString(),
            scopes = scopeList(root["scopes"] ?: root["scope"]),
            cohort = cohort(root["cohort"]),
            concurrency = (root["concurrency"] as? Number)?.toInt() ?: 100,
            insecure = root["insecure"] as? Boolean ?: false,
            aslProd = (root["asl-prod"] ?: root["aslProd"]) as? Boolean ?: false,
            caCerts = stringList(root["ca-cert"] ?: root["caCert"] ?: root["caCerts"]),
            connectTimeoutMs = (root["connect-timeout"] as? Number)?.toLong()?.times(1000),
            requestTimeoutMs = (root["request-timeout"] as? Number)?.toLong()?.times(1000),
            attemptTimeoutMs = ((root["attempt-timeout"] as? Number)?.toLong() ?: 30L) * 1000,
            maxLiveClients = (root["max-live-clients"] as? Number)?.toInt()?.coerceAtLeast(1),
            abortOnFailFraction = ((root["abort-on-fail-pct"] as? Number)?.toInt() ?: 90)
                .let { if (it >= 100) null else it.coerceAtLeast(1) / 100.0 },
            randomClients = (root["random-clients"] as? Boolean) ?: false,
            durationMs = root["duration"]?.let { duration(it.toString()) },
            scenario = scenario(root["scenario"]?.toString()),
            request = (root["request"] as? Map<*, *>)?.let { request(it) },
            ramp = (root["ramp"] as? Map<*, *>)?.let { ramp(it) },
            popp = (root["popp"] as? Map<*, *>)?.let { popp(it) },
            warmup = warmup,
            cycle = cycle,
        )
    }

    /**
     * `cohort:` is either a bare number (that many institutions, one client each) or a block with
     * `institutions`, `clients-per-institution` (`N`, `a..b`, or `[a, b]`), and an optional `seed`.
     */
    private fun cohort(v: Any?): CohortSpec = when (v) {
        null -> CohortSpec(institutions = 100, clientsPerInstitution = 1..1)
        is Number -> CohortSpec(institutions = v.toInt(), clientsPerInstitution = 1..1)
        is Map<*, *> -> CohortSpec(
            institutions = (v["institutions"] as? Number)?.toInt()?.coerceAtLeast(1)
                ?: error("cohort block needs 'institutions'"),
            clientsPerInstitution = clientsRange(v["clients-per-institution"]),
            seed = (v["seed"] as? Number)?.toLong(),
        )
        else -> error("cohort must be a number or a mapping")
    }

    private fun clientsRange(v: Any?): IntRange = when (v) {
        null -> 1..1
        is Number -> v.toInt().coerceAtLeast(1).let { it..it }
        is List<*> -> v.mapNotNull { (it as? Number)?.toInt() }.let { ns ->
            (ns.minOrNull() ?: 1).coerceAtLeast(1)..(ns.maxOrNull() ?: 1).coerceAtLeast(1)
        }
        is String -> Regex("""(\d+)\s*(?:\.\.|-|–)\s*(\d+)""").matchEntire(v.trim())?.let {
            it.groupValues[1].toInt().coerceAtLeast(1)..it.groupValues[2].toInt().coerceAtLeast(1)
        } ?: v.trim().toInt().coerceAtLeast(1).let { it..it }
        else -> 1..1
    }

    private fun scenario(s: String?): Scenario = when (s?.lowercase()?.replace('_', '-')) {
        null, "login-storm" -> Scenario.LOGIN_STORM
        "login-and-vsdm-storm" -> Scenario.LOGIN_AND_VSDM_STORM
        "refresh-storm" -> Scenario.REFRESH_STORM
        "register-storm" -> Scenario.REGISTER_STORM
        "discover-storm" -> Scenario.DISCOVER_STORM
        else -> error("unknown scenario '$s' (login-storm | login-and-vsdm-storm | refresh-storm | register-storm | discover-storm)")
    }

    private fun request(m: Map<*, *>): VsdmRequest = VsdmRequest(
        url = m["url"]?.toString() ?: error("request block needs a 'url'"),
        method = m["method"]?.toString()?.uppercase() ?: "GET",
        headers = (m["headers"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() }
            ?: emptyMap(),
        popp = m["popp"] as? Boolean ?: true,
        expectStatus = statusSet(m["expect-status"] ?: m["expectStatus"]),
        opLabel = m["op"]?.toString() ?: "vsdm",
    )

    private fun ramp(m: Map<*, *>): RampSpec = RampSpec(
        startPerMin = (m["start"] as? Number)?.toInt() ?: 500,
        stepPerMin = (m["step"] as? Number)?.toInt() ?: 500,
        stepEverySec = (m["step-interval"] as? Number)?.toLong() ?: 20,
        maxPerMin = (m["ceiling"] as? Number)?.toInt() ?: 5000,
        maxFailPct = (m["max-fail-pct"] as? Number)?.toInt() ?: 10,
        maxP99Ms = (m["max-p99"] as? Number)?.toLong() ?: 5000,
        durationMs = m["duration"]?.let { duration(it.toString()) },
    )

    private fun popp(m: Map<*, *>): PoppSpec = PoppSpec(
        egkDir = (m["egk-dir"] ?: m["egkDir"])?.toString(),
        perIdentity = (m["per-identity"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1,
        serviceUrl = m["service-url"]?.toString() ?: DEFAULT_POPP_SERVICE_URL,
        kartosBin = m["kartos-bin"]?.toString() ?: "kartos",
        scope = m["scope"]?.toString() ?: "popp",
        concurrency = (m["concurrency"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 8,
    )

    private fun statusSet(v: Any?): Set<Int> = when (v) {
        null -> setOf(200, 304)
        is List<*> -> v.mapNotNull { (it as? Number)?.toInt() }.toSet()
        is Number -> setOf(v.toInt())
        else -> setOf(200, 304)
    }

    private fun scopeList(v: Any?): List<String>? = when (v) {
        null -> null
        is List<*> -> v.map { it.toString() }
        else -> listOf(v.toString())
    }

    private fun stringList(v: Any?): List<String> = when (v) {
        null -> emptyList()
        is List<*> -> v.map { it.toString() }
        else -> listOf(v.toString())
    }

    private fun phase(m: Map<*, *>, default: String): Phase {
        val name = m["name"]?.toString() ?: default
        val durationMs = duration(m["duration"]?.toString() ?: error("phase '$name' is missing 'duration'"))
        return if (m["peak"] != null) {
            Phase.spikes(
                name = name,
                basePerMin = int(m, "base", name),
                peakPerMin = int(m, "peak", name),
                prob = (m["probability"] as? Number)?.toDouble() ?: error("spike phase '$name' needs 'probability'"),
                durationMs = durationMs,
                bucketMs = m["bucket"]?.let { duration(it.toString()) } ?: 2000L,
            )
        } else {
            Phase.hold(name, int(m, "rate", name), durationMs)
        }
    }

    private fun int(m: Map<*, *>, key: String, phase: String): Int =
        (m[key] as? Number)?.toInt() ?: error("phase '$phase' field '$key' must be a number")

    private fun duration(s: String): Long {
        val match = Regex("""(\d+)\s*(ms|s|m|h)?""").matchEntire(s.trim())
            ?: error("bad duration: '$s'")
        val n = match.groupValues[1].toLong()
        return when (match.groupValues[2]) {
            "ms" -> n
            "", "s" -> n * 1000
            "m" -> n * 60_000
            "h" -> n * 3_600_000
            else -> error("bad duration unit in '$s'")
        }
    }
}
