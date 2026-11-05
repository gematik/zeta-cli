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
package de.gematik.zeta.cli.trace

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TracerEventTest {
    private val delivered = mutableListOf<Span>()

    @AfterEach
    fun resetTracer() = Tracer.reset()

    @Test
    fun `event delivers the finished span with its children to the sink`() = runBlocking {
        Tracer.initDaemon { delivered += it }

        val result = Tracer.event("mint", mapOf("a" to 1)) {
            Tracer.spanSuspend("child") {}
            "done"
        }

        assertEquals("done", result)
        val span = delivered.single()
        assertEquals("mint", span.name)
        assertEquals(1, span.attrs["a"])
        assertEquals(listOf("child"), span.children.map { it.name })
        assertTrue(span.durationMs >= 0)
    }

    @Test
    fun `event is a no-op when tracing is disabled`() = runBlocking {
        val seen = Tracer.event("mint") { it }

        assertSame(NoopSpan, seen)
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `failing event is marked, delivered and rethrown`() {
        Tracer.initDaemon { delivered += it }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { Tracer.event<Unit>("mint") { error("boom") } }
        }

        val span = delivered.single()
        assertFalse(span.ok)
        assertEquals("IllegalStateException", span.errorType)
        assertTrue(span.durationMs >= 0)
    }

    @Test
    fun `event has no parent and leaves the thread-local as it found it`() = runBlocking {
        Tracer.initDaemon { delivered += it }

        Tracer.spanSuspend("outer") { outer ->
            Tracer.event("mint") {}
            assertSame(outer, Tracer.current())
            assertTrue(outer.children.isEmpty())
        }

        assertNull(delivered.single().parent)
        assertNull(Tracer.current())
    }

    @Test
    fun `a throwing sink does not break the traced work`() = runBlocking {
        Tracer.initDaemon { error("disk full") }

        assertEquals("ok", Tracer.event("mint") { "ok" })
    }

    @Test
    fun `reset clears the sink`() = runBlocking {
        Tracer.initDaemon { delivered += it }
        Tracer.reset()
        Tracer.init()

        Tracer.event("mint") {}

        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `spanUntil stops the clock at the signal, not at the end of the block`() = runBlocking {
        Tracer.init()
        lateinit var span: Span

        Tracer.spanSuspend("outer") { outer ->
            Tracer.spanUntil("ws.open") { opened ->
                opened()
                span = outer.children.single()
                val atSignal = span.durationMs
                assertTrue(atSignal >= 0)
                delay(20)
                assertEquals(atSignal, span.durationMs)
            }
        }
        assertTrue(span.ok)
    }

    @Test
    fun `spanUntil fails and ends the span when the block dies before the signal`() {
        Tracer.init()
        lateinit var outerSpan: Span

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                Tracer.spanSuspend("outer") { outer ->
                    outerSpan = outer
                    Tracer.spanUntil<Unit>("ws.open") { error("refused") }
                }
            }
        }

        val span = outerSpan.children.single()
        assertFalse(span.ok)
        assertEquals("IllegalStateException", span.errorType)
        assertTrue(span.durationMs >= 0)
    }

    @Test
    fun `spanUntil stays ok when the block dies after the signal`() {
        Tracer.init()
        lateinit var outerSpan: Span

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                Tracer.spanSuspend("outer") { outer ->
                    outerSpan = outer
                    Tracer.spanUntil<Unit>("ws.open") { opened ->
                        opened()
                        error("scenario failed")
                    }
                }
            }
        }

        assertTrue(outerSpan.children.single().ok)
    }

    @Test
    fun `withTracedLock releases the lock when the block throws`() {
        Tracer.init()
        val lock = Mutex()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { lock.withTracedLock { error("boom") } }
        }

        assertFalse(lock.isLocked)
    }

    @Test
    fun `withTracedLock cancelled while waiting neither runs the block nor keeps the lock`() = runBlocking {
        Tracer.init()
        val lock = Mutex(locked = true)
        var ran = false

        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { lock.withTracedLock { ran = true } }
        waiter.cancelAndJoin()
        lock.unlock()

        assertFalse(ran)
        assertFalse(lock.isLocked)
    }
}
