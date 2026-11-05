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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class TraceFileTest {
    @TempDir
    lateinit var dir: Path

    @AfterEach
    fun resetTracer() = Tracer.reset()

    private fun lines(file: Path): List<JsonObject> =
        Files.readAllLines(file).map { Json.parseToJsonElement(it).jsonObject }

    @Test
    fun `writes one span tree per line with children and attrs`() = runBlocking {
        val file = dir.resolve("trace.jsonl")
        Tracer.initDaemon(TraceFile(file)::write)
        val before = System.currentTimeMillis()

        Tracer.event("mint", mapOf("egkHandle" to false)) { span ->
            span.attr("status", 200)
            Tracer.spanSuspend("card.apdu", mapOf("hdr" to "00A4040C")) {}
        }

        val root = lines(file).single()
        assertEquals("mint", root["name"]!!.jsonPrimitive.content)
        assertEquals(0.0, root["startMs"]!!.jsonPrimitive.double)
        assertTrue(root["durationMs"]!!.jsonPrimitive.double >= 0)
        assertTrue(root["ok"]!!.jsonPrimitive.boolean)
        assertTrue(root["ts"]!!.jsonPrimitive.long in (before - 1000)..(System.currentTimeMillis() + 1000))
        assertFalse(root["attrs"]!!.jsonObject["egkHandle"]!!.jsonPrimitive.boolean)
        assertEquals(200, root["attrs"]!!.jsonObject["status"]!!.jsonPrimitive.int)

        val child = root["children"]!!.jsonArray.single().jsonObject
        assertEquals("card.apdu", child["name"]!!.jsonPrimitive.content)
        assertEquals("00A4040C", child["attrs"]!!.jsonObject["hdr"]!!.jsonPrimitive.content)
        assertTrue(child["startMs"]!!.jsonPrimitive.double >= 0)
        assertTrue("ts" !in child)
    }

    @Test
    fun `failures record the exception type and never its message`() = runBlocking {
        val file = dir.resolve("trace.jsonl")
        Tracer.initDaemon(TraceFile(file)::write)

        runCatching {
            Tracer.event<Unit>("mint") {
                throw IllegalArgumentException("Multiple eGKs visible: kvnr=A123456789")
            }
        }

        val text = Files.readString(file)
        assertFalse(text.contains("A123456789"), text)
        val root = lines(file).single()
        assertFalse(root["ok"]!!.jsonPrimitive.boolean)
        assertEquals("IllegalArgumentException", root["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parallel events each get a whole line`() = runBlocking {
        val file = dir.resolve("trace.jsonl")
        Tracer.initDaemon(TraceFile(file)::write)

        (1..50).map { i ->
            async(Dispatchers.IO) { Tracer.event("warm", mapOf("i" to i)) {} }
        }.awaitAll()

        val seen = lines(file).map { it["attrs"]!!.jsonObject["i"]!!.jsonPrimitive.int }
        assertEquals((1..50).toSet(), seen.toSet())
    }

    @Test
    fun `an unwritable path does not throw`() {
        val blocker = Files.createFile(dir.resolve("not-a-dir"))
        val span = Span("mint", parent = null).also { it.end() }

        TraceFile(blocker.resolve("trace.jsonl")).write(span)
    }

    @Test
    fun `a new file is readable by its owner only`() {
        val file = dir.resolve("sub/trace.jsonl")

        TraceFile(file).write(Span("mint", parent = null).also { it.end() })

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }

    @Test
    fun `a file system without POSIX permissions still gets the trace`() {
        val file = dir.resolve("trace.jsonl")

        TraceFile(file, ownerOnly = false).write(Span("mint", parent = null).also { it.end() })

        assertEquals("mint", lines(file).single()["name"]!!.jsonPrimitive.content)
    }
}
