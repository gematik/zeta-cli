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

import de.gematik.zeta.cli.trace.Span
import de.gematik.zeta.cli.trace.Tracer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PoppTraceTest {
    @BeforeEach
    fun enableTracer() = Tracer.init()

    @AfterEach
    fun resetTracer() = Tracer.reset()

    private fun traced(block: suspend (Span) -> Unit): Span {
        lateinit var flow: Span
        runBlocking { Tracer.spanSuspend("popp.flow") { flow = it; block(it) } }
        return flow
    }

    @Test
    fun `a scenario round records its size and hangs under the flow`() {
        val flow = traced { parent ->
            tracedScenarioRound(parent, round = 2) { listOf("9000", "AABB9000") }
        }

        val round = flow.children.single()
        assertEquals("scenario", round.name)
        assertEquals(2, round.attrs["round"])
        assertEquals(2, round.attrs["apdus"])
        assertEquals(6, round.attrs["respBytes"])
    }

    @Test
    fun `work inside a round nests under it rather than under the flow`() {
        val flow = traced { parent ->
            tracedScenarioRound(parent, round = 1) {
                Tracer.spanSuspend("connector.secureSendApdu") {}
                listOf("9000")
            }
        }

        assertEquals(listOf("scenario"), flow.children.map { it.name })
        assertEquals(listOf("connector.secureSendApdu"), flow.children.single().children.map { it.name })
    }

    @Test
    fun `an APDU span holds the header, sizes and status but no payload`() {
        val command = "00B09100" + "CAFE"
        val flow = traced {
            assertEquals("DEADBEEF9000", tracedApdu(command) { "DEADBEEF9000" })
        }

        val apdu = flow.children.single()
        assertEquals("card.apdu", apdu.name)
        assertEquals(
            mapOf("hdr" to "00B09100", "cmdBytes" to 6, "respBytes" to 6, "sw" to "9000"),
            apdu.attrs,
        )
        assertFalse(apdu.attrs.values.any { it.toString().contains("DEADBEEF") || it.toString().contains("CAFE") })
    }
}
