package de.gematik.zeta.cli.probe

import com.github.ajalt.clikt.core.UsageError
import de.gematik.zeta.catalog.ServiceCatalog
import de.gematik.zeta.catalog.ServiceInstance
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProbeTargetsTest {
    private val poppUrl = "wss://popp.dev.poppservice.de/popp/practitioner/api/v1/token-generation-ehc"
    private val catalog = ServiceCatalog(
        serviceInstances = mapOf(
            "vsdm-1" to ServiceInstance("vsdm", "https://vsdm-dev.tk.de"),
            "other" to ServiceInstance("something-else", "https://not-vsdm.example"),
        ),
    )

    @Test
    fun `pairs endpoints with scopes by position`() {
        assertEquals(
            listOf("https://a/" to "s1", "https://b/" to "s2"),
            pairEndpoints(listOf("https://a/", "https://b/"), listOf("s1", "s2")),
        )
        assertEquals(emptyList<Pair<String, String>>(), pairEndpoints(emptyList(), emptyList()))
    }

    @Test
    fun `rejects mismatched endpoint and scope counts`() {
        assertThrows(UsageError::class.java) { pairEndpoints(listOf("https://a/", "https://b/"), listOf("s1")) }
        assertThrows(UsageError::class.java) { pairEndpoints(emptyList(), listOf("s1")) }
    }

    @Test
    fun `catalog targets come first, extras are reduced to origins and deduplicated`() {
        val targets = resolveTargets(
            catalog,
            poppUrl,
            includeCatalog = true,
            extras = listOf("https://x.example:8443/some/path?q=1" to "foo", "https://vsdm-dev.tk.de/other" to "vsdservice"),
        )
        assertEquals(
            listOf(
                ProbeTarget("https://vsdm-dev.tk.de/", listOf("vsdservice"), "vsdm-1"),
                ProbeTarget("https://popp.dev.poppservice.de/", listOf("popp"), "popp"),
                ProbeTarget("https://x.example:8443/", listOf("foo"), "x.example"),
            ),
            targets,
        )
    }

    @Test
    fun `same origin with a different scope is a separate target`() {
        val targets = resolveTargets(null, poppUrl, includeCatalog = false, extras = listOf("https://a/" to "s1", "https://a/" to "s2"))
        assertEquals(2, targets.size)
    }

    @Test
    fun `no-catalog keeps only the extras`() {
        val targets = resolveTargets(catalog, poppUrl, includeCatalog = false, extras = listOf("https://a/" to "s1"))
        assertEquals(listOf(ProbeTarget("https://a/", listOf("s1"), "a")), targets)
    }

    @Test
    fun `rejects relative endpoints`() {
        assertThrows(UsageError::class.java) { resolveTargets(null, poppUrl, false, listOf("vsdm.example" to "s")) }
    }

    @Test
    fun `round robin wraps and survives list changes`() {
        val a = ProbeTarget("https://a/", listOf("s"), "a")
        val b = ProbeTarget("https://b/", listOf("s"), "b")
        val c = ProbeTarget("https://c/", listOf("s"), "c")
        assertNull(nextTarget(emptyList(), null))
        assertEquals(a, nextTarget(listOf(a, b), null))
        assertEquals(b, nextTarget(listOf(a, b), a.key))
        assertEquals(a, nextTarget(listOf(a, b), b.key))
        assertEquals(c, nextTarget(listOf(a, b, c), b.key))
        assertEquals(a, nextTarget(listOf(a, c), b.key))
    }

    @Test
    fun `tick spreads the interval over the targets`() {
        assertEquals(3.seconds, tickInterval(60.seconds, 20))
        assertEquals(60.seconds, tickInterval(60.seconds, 0))
        assertEquals(1.seconds, tickInterval(10.seconds, 100))
        assertEquals(9.minutes, tickInterval(180.minutes, 20))
    }

    @Test
    fun `registry reports added and removed targets`() {
        val a = ProbeTarget("https://a/", listOf("s"), "a")
        val b = ProbeTarget("https://b/", listOf("s"), "b")
        val c = ProbeTarget("https://c/", listOf("s"), "c")
        val registry = TargetRegistry(listOf(a, b))
        val (added, removed) = registry.update(listOf(b, c))
        assertEquals(listOf(c), added)
        assertEquals(listOf(a), removed)
        assertEquals(listOf(b, c), registry.current)
    }
}
