package de.gematik.zeta.cli.serve

import de.gematik.zeta.catalog.ServiceCatalog
import de.gematik.zeta.catalog.ServiceInstance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WarmEndpointsTest {
    private val poppUrl = "wss://popp.dev.poppservice.de/popp/practitioner/api/v1/token-generation-ehc"

    @Test
    fun `enumerates vsdm instances plus popp, mapped to resource origins`() {
        val catalog = ServiceCatalog(
            serviceInstances = mapOf(
                "vsdm-1" to ServiceInstance("vsdm", "https://vsdm-dev.tk.de"),
                "vsdm-2" to ServiceInstance("vsdm", "https://vsdm-rudev.apimisc.de"),
                "other" to ServiceInstance("something-else", "https://not-vsdm.example"),
            ),
        )

        val endpoints = warmEndpoints(catalog, poppUrl)

        assertEquals(
            setOf("https://vsdm-dev.tk.de/", "https://vsdm-rudev.apimisc.de/"),
            endpoints.filter { it.second == listOf("vsdservice") }.map { it.first }.toSet(),
        )
        // popp: the wss URL maps to its https resource origin, scope popp.
        assertTrue(endpoints.contains("https://popp.dev.poppservice.de/" to listOf("popp")))
        // the non-vsdm instance is ignored.
        assertTrue(endpoints.none { it.first.contains("not-vsdm") })
    }

    @Test
    fun `a null catalog still warms the popp endpoint`() {
        val endpoints = warmEndpoints(null, poppUrl)
        assertEquals(listOf("https://popp.dev.poppservice.de/" to listOf("popp")), endpoints)
    }
}
