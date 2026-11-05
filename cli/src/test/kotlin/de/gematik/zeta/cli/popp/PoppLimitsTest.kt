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
package de.gematik.zeta.cli.popp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class PoppLimitsTest {
    @Test
    fun `opening past the limit fails with a timeout`() {
        assertThrows(PoppTimeoutException::class.java) {
            runBlocking { withOpenLimit<Unit>(100.milliseconds) { awaitCancellation() } }
        }
    }

    @Test
    fun `the limit stops counting at the signal`() = runBlocking {
        val result = withOpenLimit(100.milliseconds) { opened ->
            opened()
            delay(300)
            "done"
        }

        assertEquals("done", result)
    }

    @Test
    fun `without a limit the block just runs`() = runBlocking {
        assertEquals("done", withOpenLimit(null) { "done" })
    }

    @Test
    fun `a failure while opening keeps its own type`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { withOpenLimit(5_000.milliseconds) { error("refused") } }
        }
    }

    // The connection itself stays open until the HTTP client's own read timeout: the SDK builds that
    // client inside ws(), and cancelling the upgrade does not reach OkHttp. What the limit guarantees
    // is that the mint returns, and with it the daemon's request lock.
    @Test
    fun `the limit returns promptly from a real server that never answers`() {
        ServerSocket(0).use { server ->
            val acceptor = thread(isDaemon = true) { runCatching { server.accept() } }
            HttpClient(OkHttp) { install(WebSockets) }.use { client ->
                val elapsed = measureTime {
                    assertThrows(PoppTimeoutException::class.java) {
                        runBlocking {
                            withOpenLimit(300.milliseconds) {
                                client.webSocket("ws://127.0.0.1:${server.localPort}/") { awaitCancellation() }
                            }
                        }
                    }
                }
                assertTrue(elapsed < 2.seconds, "took $elapsed")
            }
            acceptor.join(1_000)
        }
    }

    @Test
    fun `cleanup still runs after the coroutine was cancelled`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var cleanedUp = false

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                cleanupEvenIfCancelled {
                    delay(10)
                    cleanedUp = true
                }
            }
        }
        started.await()
        job.cancelAndJoin()

        assertTrue(cleanedUp)
    }

    @Test
    fun `cleanup gives up at its own limit`() = runBlocking {
        assertNull(cleanupEvenIfCancelled<Unit>(50.milliseconds) { awaitCancellation() })
    }
}
