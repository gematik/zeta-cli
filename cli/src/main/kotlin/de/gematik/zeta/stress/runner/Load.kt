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
package de.gematik.zeta.stress.runner

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

private val log = KotlinLogging.logger {}

/** How the harness paces work across the fleet. */
sealed interface LoadShape {
    /** Release every item at once (thundering herd), bounded only by the concurrency cap. */
    data object Burst : LoadShape

    /** Admit items at a steady [perMinute] rate via a token bucket. */
    data class Steady(val perMinute: Int) : LoadShape
}

/**
 * A monotonic token bucket. [acquire] suspends until the next slot is due so the aggregate
 * admission rate does not exceed [perMinute]. `elapsedMs` is injected (not `System`-read here)
 * so callers own the clock; the runner passes a monotonic source.
 */
class RateLimiter(perMinute: Int, private val clockMs: () -> Long) {
    private val intervalMs: Double = 60_000.0 / perMinute.coerceAtLeast(1)
    private val mutex = Mutex()
    private var nextSlot = Double.NaN

    suspend fun acquire() {
        val waitMs = mutex.withLock {
            val now = clockMs().toDouble()
            if (nextSlot.isNaN() || now > nextSlot) nextSlot = now
            val due = nextSlot
            nextSlot += intervalMs
            (due - now).toLong()
        }
        if (waitMs > 0) delay(waitMs)
    }
}

/**
 * Drives [action] over [items] under a [LoadShape], capping in-flight work at [concurrency].
 * Returns once every item has completed. Exceptions from [action] are the action's own concern
 * (it should record failures into the [Reporter]); a thrown exception cancels the batch.
 */
suspend fun <T> runLoad(
    items: List<T>,
    concurrency: Int,
    shape: LoadShape,
    clockMs: () -> Long,
    action: suspend (T) -> Unit,
) = coroutineScope {
    val gate = Semaphore(concurrency)
    val limiter = (shape as? LoadShape.Steady)?.let { RateLimiter(it.perMinute, clockMs) }
    val barrier = if (shape is LoadShape.Burst) CompletableDeferred<Unit>() else null

    log.info { "Dispatching ${items.size} items (concurrency=$concurrency, shape=$shape)" }

    val jobs = items.map { item ->
        launch {
            barrier?.await()
            limiter?.acquire()
            gate.withPermit { action(item) }
        }
    }
    barrier?.complete(Unit)
    jobs.forEach { it.join() }
}
