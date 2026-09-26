package de.gematik.zeta.cli.probe

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

internal data class BackoffEntry(val kind: ProbeKind, val target: ProbeTarget, val failures: Int, val dueAtEpochMs: Long)

/**
 * Exponential hold-off per (kind, target): after `k` consecutive failures the next run is due no earlier
 * than `min(interval * 2^k, maxDelay)` after the failure; a success clears the entry. Every call takes
 * `now` so the scheduler tests can drive it with virtual time. A [maxDelay] of zero disables it.
 */
internal class BackoffPolicy(
    private val maxDelay: Duration,
    private val interval: (ProbeKind) -> Duration,
) {
    private data class State(val target: ProbeTarget, val failures: Int, val dueAtMs: Long)

    private val state = ConcurrentHashMap<Pair<ProbeKind, String>, State>()

    val enabled: Boolean get() = maxDelay > Duration.ZERO

    fun shouldRun(kind: ProbeKind, key: String, nowMs: Long): Boolean =
        !enabled || (state[kind to key]?.dueAtMs ?: Long.MIN_VALUE) <= nowMs

    fun record(outcome: ProbeOutcome, nowMs: Long) = record(outcome.kind, outcome.target, outcome.result == ProbeResult.OK, nowMs)

    fun record(kind: ProbeKind, target: ProbeTarget, ok: Boolean, nowMs: Long) {
        if (!enabled) return
        val key = kind to target.key
        if (ok) {
            state.remove(key)
            return
        }
        val failures = (state[key]?.failures ?: 0) + 1
        state[key] = State(target, failures, nowMs + delayFor(kind, failures).inWholeMilliseconds)
    }

    fun forget(target: ProbeTarget) {
        state.keys.removeIf { it.second == target.key }
    }

    fun entries(): List<BackoffEntry> = state.map { (key, s) -> BackoffEntry(key.first, s.target, s.failures, s.dueAtMs) }

    internal fun delayFor(kind: ProbeKind, failures: Int): Duration =
        minOf(interval(kind) * (1 shl failures.coerceIn(1, 30)), maxDelay)
}
