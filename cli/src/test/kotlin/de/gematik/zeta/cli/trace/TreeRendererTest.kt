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
