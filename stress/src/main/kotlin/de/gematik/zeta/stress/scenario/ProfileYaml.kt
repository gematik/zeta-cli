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
 * A parsed `run <profile.yaml>`: the run settings (any may be omitted → fall back to the CLI flag /
 * its default) plus the rate waveform. [schedule] stitches the phases via [Rates.profile].
 */
class RunProfile(
    val resource: String?,
    val scopes: List<String>?,
    val cohort: Int?,
    val concurrency: Int?,
    val insecure: Boolean?,
    val durationMs: Long?,
    private val warmup: Phase?,
    private val cycle: List<Phase>,
) {
    val warmupMs: Long = warmup?.durationMs ?: 0L
    val cycleMs: Long = cycle.sumOf { it.durationMs }

    fun schedule(): RateSchedule = Rates.profile(warmup, cycle)

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
 * Loads a YAML run profile:
 *
 * ```yaml
 * resource: https://guard.example
 * scope: zero:audience          # or scopes: [a, b]
 * cohort: 5000
 * concurrency: 200
 * insecure: true
 * duration: 5m                  # total run time (ms/s/m/h; bare = seconds); omit → one cycle
 *
 * warmup:
 *   name: warmup
 *   rate: 800
 *   duration: 60s
 * cycle:
 *   - name: burst
 *     rate: 5000
 *     duration: 3m
 *   - name: spikes
 *     base: 600
 *     peak: 4000
 *     probability: 0.2
 *     duration: 90s          # random spikes, bucket: 2s default
 * ```
 *
 * Every phase needs `duration`. A phase with `peak` is a spike phase (needs `base`, `peak`,
 * `probability`); otherwise a constant hold (needs `rate`). Durations accept `ms`/`s`/`m`/`h`
 * (bare = seconds).
 */
object ProfileYaml {
    fun load(path: Path): RunProfile {
        val root = Files.newBufferedReader(path).use { Yaml().load<Map<String, Any?>>(it) }
            ?: error("empty profile: $path")

        val scopes = when (val s = root["scopes"] ?: root["scope"]) {
            null -> null
            is List<*> -> s.map { it.toString() }
            else -> listOf(s.toString())
        }
        val warmup = (root["warmup"] as? Map<*, *>)?.let { phase(it, default = "warmup") }
        val cycleRaw = root["cycle"] as? List<*>
            ?: error("profile must define a 'cycle:' list of phases")
        val cycle = cycleRaw
            .mapIndexed { i, e -> phase(e as? Map<*, *> ?: error("cycle entry ${i + 1} must be a mapping"), default = "phase${i + 1}") }
        require(cycle.isNotEmpty()) { "profile 'cycle' is empty" }

        return RunProfile(
            resource = root["resource"]?.toString(),
            scopes = scopes,
            cohort = (root["cohort"] as? Number)?.toInt(),
            concurrency = (root["concurrency"] as? Number)?.toInt(),
            insecure = root["insecure"] as? Boolean,
            durationMs = root["duration"]?.let { duration(it.toString()) },
            warmup = warmup,
            cycle = cycle,
        )
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
