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

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TreeRendererTest {

    @BeforeEach
    fun enableTracer() = Tracer.init()

    @AfterEach
    fun resetTracer() = Tracer.reset()

    @Test
    fun `renders nested tree with ASCII connectors`() {
        Tracer.root("cli.run", attrs = mapOf("argv" to "connector get cards")) {
            Tracer.span("sdk.session") {
                Tracer.span("sdk.init") {}
                Tracer.span("connector.getAllCards") {}
            }
        }
        val tree = renderSpanTree(Tracer.root!!)
        // Root line
        assertTrue(tree.lines().first().startsWith("cli.run ("), tree)
        assertTrue(tree.contains("argv=\"connector get cards\""), tree)
        // Tree structure
        assertTrue(tree.contains("└─ sdk.session"), tree)
        assertTrue(tree.contains("├─ sdk.init"), tree)
        assertTrue(tree.contains("└─ connector.getAllCards"), tree)
    }

    @Test
    fun `failed span gets an error marker`() {
        runCatching {
            Tracer.root("root") {
                Tracer.span("a") { error("boom") }
            }
        }
        val tree = renderSpanTree(Tracer.root!!)
        assertTrue(tree.contains("! boom"), tree)
    }
}
