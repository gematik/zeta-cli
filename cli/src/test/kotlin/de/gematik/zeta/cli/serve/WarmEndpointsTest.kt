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
