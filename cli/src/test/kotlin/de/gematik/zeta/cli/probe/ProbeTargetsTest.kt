package de.gematik.zeta.cli.probe

import com.github.ajalt.clikt.core.UsageError
import de.gematik.zeta.catalog.CatalogStore
import de.gematik.zeta.catalog.Environment
import de.gematik.zeta.catalog.ServiceCatalog
import de.gematik.zeta.catalog.ServiceInstance
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProbeTargetsTest {
    private val poppUrl = "wss://popp.dev.poppservice.de/popp/practitioner/api/v1/token-generation-ehc"
    private val catalog = ServiceCatalog(
        serviceInstances = mapOf(
            "vsdm-1" to ServiceInstance("vsdm", "https://vsdm-dev.tk.de"),
            "vsdm-2" to ServiceInstance("vsdm", "https://vsdm-rudev.apimisc.de"),
            "other" to ServiceInstance("something-else", "https://not-vsdm.example"),
        ),
    )
    private fun t(env: Environment, resource: String, scope: String, service: String, type: String = service, labels: Map<String, String> = emptyMap()) =
        ProbeTarget(env, resource, listOf(scope), service, type, labels)

    @Test
    fun `pairs endpoints with scopes, names and types by position`() {
        assertEquals(
            listOf(ExplicitEndpoint("https://a/", "s1", "n1", "t1"), ExplicitEndpoint("https://b/", "s2", "n2", "t2")),
            pairEndpoints(listOf("https://a/", "https://b/"), listOf("s1", "s2"), listOf("n1", "n2"), listOf("t1", "t2")),
        )
        assertEquals(listOf(ExplicitEndpoint("https://a/", "s1")), pairEndpoints(listOf("https://a/"), listOf("s1")))
        assertEquals(emptyList<ExplicitEndpoint>(), pairEndpoints(emptyList(), emptyList()))
    }

    @Test
    fun `rejects mismatched counts and duplicate names`() {
        assertThrows(UsageError::class.java) { pairEndpoints(listOf("https://a/", "https://b/"), listOf("s1")) }
        assertThrows(UsageError::class.java) { pairEndpoints(emptyList(), listOf("s1")) }
        assertThrows(UsageError::class.java) { pairEndpoints(listOf("https://a/", "https://b/"), listOf("s1", "s2"), listOf("n1")) }
        assertThrows(UsageError::class.java) { pairEndpoints(listOf("https://a/", "https://b/"), listOf("s1", "s2"), types = listOf("t1")) }
        assertThrows(UsageError::class.java) { pairEndpoints(listOf("https://a/", "https://b/"), listOf("s1", "s2"), listOf("n", "n")) }
    }

    @Test
    fun `catalog targets come first with their type, extras are reduced to origins and deduplicated`() {
        val targets = resolveTargets(
            Environment.DEV, catalog, poppUrl, includeCatalog = true,
            extras = listOf(ExplicitEndpoint("https://x.example:8443/some/path?q=1", "foo"), ExplicitEndpoint("https://vsdm-dev.tk.de/other", "vsdservice")),
        )
        assertEquals(
            listOf(
                t(Environment.DEV, "https://vsdm-dev.tk.de/", "vsdservice", "vsdm-1", "vsdm"),
                t(Environment.DEV, "https://vsdm-rudev.apimisc.de/", "vsdservice", "vsdm-2", "vsdm"),
                t(Environment.DEV, "https://popp.dev.poppservice.de/", "popp", "popp"),
                t(Environment.DEV, "https://x.example:8443/", "foo", "x.example"),
            ),
            targets,
        )
    }

    @Test
    fun `explicit endpoints honour name and type, defaulting to host and slug`() {
        val targets = resolveTargets(
            Environment.REF, null, poppUrl, includeCatalog = false,
            extras = listOf(ExplicitEndpoint("https://a/", "s", "foo", "test"), ExplicitEndpoint("https://b/", "s", "bar"), ExplicitEndpoint("https://c/", "s")),
        )
        assertEquals(
            listOf(t(Environment.REF, "https://a/", "s", "foo", "test"), t(Environment.REF, "https://b/", "s", "bar"), t(Environment.REF, "https://c/", "s", "c")),
            targets,
        )
    }

    @Test
    fun `labels are attached by type to every target of that type`() {
        val labels = mapOf("vsdm" to mapOf("criticality" to "high"), "test" to mapOf("team" to "x"))
        val targets = resolveTargets(
            Environment.DEV, catalog, poppUrl, includeCatalog = true,
            extras = listOf(ExplicitEndpoint("https://a/", "s", "foo", "test")), typeLabels = labels,
        )
        assertEquals(mapOf("criticality" to "high"), targets.first { it.service == "vsdm-1" }.labels)
        assertEquals(mapOf("criticality" to "high"), targets.first { it.service == "vsdm-2" }.labels)
        assertEquals(emptyMap<String, String>(), targets.first { it.service == "popp" }.labels)
        assertEquals(mapOf("team" to "x"), targets.first { it.service == "foo" }.labels)
    }

    @Test
    fun `same origin with a different scope is a separate target`() {
        val targets = resolveTargets(Environment.DEV, null, poppUrl, false, listOf(ExplicitEndpoint("https://a/", "s1"), ExplicitEndpoint("https://a/", "s2")))
        assertEquals(2, targets.size)
    }

    @Test
    fun `rejects relative endpoints`() {
        assertThrows(UsageError::class.java) { resolveTargets(Environment.DEV, null, poppUrl, false, listOf(ExplicitEndpoint("vsdm.example", "s"))) }
    }

    @Test
    fun `key includes the environment`() {
        assertEquals("ref|https://a/|s", t(Environment.REF, "https://a/", "s", "a").key)
    }

    @Test
    fun `merge keeps env order and refuses one resource in two envs`() {
        val dev = listOf(t(Environment.DEV, "https://a/", "s", "a"))
        val ref = listOf(t(Environment.REF, "https://b/", "s", "b"))
        assertEquals(dev + ref, mergeEnvTargets(listOf(dev, ref)))
        assertThrows(UsageError::class.java) { mergeEnvTargets(listOf(dev, listOf(t(Environment.REF, "https://a/", "s", "a")))) }
    }

    @Test
    fun `parses type labels and rejects bad ones`() {
        assertEquals(
            mapOf("vsdm" to mapOf("criticality" to "high", "team" to "ops"), "popp" to mapOf("criticality" to "high")),
            parseTypeLabels(listOf("vsdm:criticality=high", "popp:criticality=high", "vsdm:team=ops", "vsdm:criticality=high")),
        )
        assertEquals(mapOf("t" to mapOf("k" to "a=b")), parseTypeLabels(listOf("t:k=a=b")))
        listOf("novalue", "vsdm:", "vsdm:k", "vsdm:k=", ":k=v", "vsdm:env=x", "vsdm:type=x", "vsdm:bad-key=1", "vsdm:1a=1", "vsdm:__x=1")
            .forEach { bad -> assertThrows(UsageError::class.java, { parseTypeLabels(listOf(bad)) }, bad) }
        assertThrows(UsageError::class.java) { parseTypeLabels(listOf("vsdm:k=1", "vsdm:k=2")) }
    }

    @Test
    fun `parses environment lists`() {
        assertEquals(listOf(Environment.DEV, Environment.REF), parseEnvironments("dev,ref"))
        assertEquals(listOf(Environment.DEV, Environment.REF), parseEnvironments("DEV ref, dev"))
        assertThrows(IllegalArgumentException::class.java) { parseEnvironments("dev,nope") }
        assertThrows(IllegalArgumentException::class.java) { parseEnvironments(" , ") }
    }

    @Test
    fun `reads the fetch time of the cached catalog`() {
        val store = object : CatalogStore {
            val data = mutableMapOf<Environment, String>()
            override fun read(env: Environment) = data[env]
            override fun write(env: Environment, value: String) { data[env] = value }
        }
        assertNull(cachedCatalogFetchedAt(store, Environment.DEV))
        store.write(Environment.DEV, """{"fetchedAtEpochSec":1700000000,"maxAgeSec":3600,"body":"{}"}""")
        assertEquals(1700000000L, cachedCatalogFetchedAt(store, Environment.DEV))
        store.write(Environment.REF, "garbage")
        assertNull(cachedCatalogFetchedAt(store, Environment.REF))
    }

    @Test
    fun `round robin wraps and survives list changes`() {
        val a = t(Environment.DEV, "https://a/", "s", "a")
        val b = t(Environment.DEV, "https://b/", "s", "b")
        val c = t(Environment.DEV, "https://c/", "s", "c")
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
        val a = t(Environment.DEV, "https://a/", "s", "a")
        val b = t(Environment.DEV, "https://b/", "s", "b")
        val c = t(Environment.DEV, "https://c/", "s", "c")
        val registry = TargetRegistry(listOf(a, b))
        val (added, removed) = registry.update(listOf(b, c))
        assertEquals(listOf(c), added)
        assertEquals(listOf(a), removed)
        assertEquals(listOf(b, c), registry.current)
        assertTrue(registry.update(listOf(b, c)).let { it.first.isEmpty() && it.second.isEmpty() })
    }
}
