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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TracerTest {

    @BeforeEach
    fun enableTracer() = Tracer.init()

    @AfterEach
    fun resetTracer() = Tracer.reset()

    @Test
    fun `root span captures children`() {
        Tracer.root("root") {
            Tracer.span("a") {}
            Tracer.span("b") {
                Tracer.span("b1") {}
            }
        }
        val root = Tracer.root!!
        assertEquals(listOf("a", "b"), root.children.map { it.name })
        assertEquals(listOf("b1"), root.children[1].children.map { it.name })
    }

    @Test
    fun `parents are set correctly on nested spans`() {
        Tracer.root("root") { root ->
            Tracer.span("child") { child ->
                assertSame(root, child.parent)
                Tracer.span("grandchild") { gc ->
                    assertSame(child, gc.parent)
                }
            }
        }
    }

    @Test
    fun `current is restored after span returns`() {
        Tracer.root("root") { root ->
            assertSame(root, Tracer.current())
            Tracer.span("a") {
                assertEquals("a", Tracer.current()?.name)
            }
            assertSame(root, Tracer.current())
        }
    }

    @Test
    fun `exception fails the span and propagates`() {
        assertThrows(IllegalStateException::class.java) {
            Tracer.root("root") {
                Tracer.span("a") {
                    error("boom")
                }
            }
        }
        val root = Tracer.root!!
        val a = root.children.single()
        assertFalse(a.ok)
        assertEquals("boom", a.error)
        // root also marked failed because the throwable escapes it
        assertFalse(root.ok)
    }

    @Test
    fun `spanSuspend propagates parent across runBlocking and dispatcher switch`() {
        Tracer.root("root") {
            runBlocking {
                Tracer.spanSuspend("outer") {
                    withContext(Dispatchers.IO) {
                        Tracer.spanSuspend("inner") { inner ->
                            assertEquals("inner", inner.name)
                            assertEquals("outer", inner.parent?.name)
                        }
                    }
                }
            }
        }
        val root = Tracer.root!!
        assertEquals(listOf("outer"), root.children.map { it.name })
        assertEquals(listOf("inner"), root.children.single().children.map { it.name })
    }

    @Test
    fun `spanUnder creates child of explicit parent ignoring current`() {
        Tracer.root("root") { root ->
            Tracer.span("popp.connect") {
                runBlocking {
                    Tracer.spanUnder(root, "popp.ws.recv") {}
                    Tracer.spanUnder(root, "popp.ws.send") {}
                }
            }
        }
        val root = Tracer.root!!
        assertEquals(listOf("popp.connect", "popp.ws.recv", "popp.ws.send"), root.children.map { it.name })
    }

    @Test
    fun `attrs are recorded on the span`() {
        Tracer.root("root") {
            Tracer.span("a", attrs = mapOf("k" to "v", "n" to 42)) { span ->
                span.attr("late", true)
            }
        }
        val a = Tracer.root!!.children.single()
        assertEquals("v", a.attrs["k"])
        assertEquals(42, a.attrs["n"])
        assertEquals(true, a.attrs["late"])
    }

    @Test
    fun `span outside any root is orphaned and still runs the block`() {
        var ran = false
        Tracer.span("orphan") { ran = true }
        assertTrue(ran)
        assertNull(Tracer.root)
    }

    @Test
    fun `when disabled blocks still run but receive NoopSpan and root stays null`() {
        Tracer.reset()  // disable
        assertFalse(Tracer.enabled)
        var rootRan = false
        var childRan = false
        Tracer.root("root") { rootSpan ->
            rootRan = true
            assertSame(NoopSpan, rootSpan)
            Tracer.span("child") { child ->
                childRan = true
                assertSame(NoopSpan, child)
                child.attr("ignored", true)
            }
        }
        assertTrue(rootRan)
        assertTrue(childRan)
        assertNull(Tracer.root)
    }

    @Test
    fun `duration is recorded`() {
        Tracer.root("root") {
            Tracer.span("a") { Thread.sleep(5) }
        }
        val a = Tracer.root!!.children.single()
        assertTrue(a.durationMs >= 4.0, "expected >= 4ms, got ${a.durationMs}")
        assertNotNull(a.endNanos)
    }
}
