/*
 * Copyright (Change Date see Readme), gematik GmbH
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the
 * European Commission – subsequent versions of the EUPL (the "Licence").
 * You may not use this work except in compliance with the Licence.
 *
 * You find a copy of the Licence in the "Licence" file or at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.
 * In case of changes by gematik GmbH find details in the "Readme" file.
 *
 * See the Licence for the specific language governing permissions and limitations under the Licence.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 */
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
