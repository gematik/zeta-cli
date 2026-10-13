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

import de.gematik.zeta.catalog.Environment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PoppServiceTest {
    @Test
    fun `dev url matches the historical DEFAULT_SERVICE_URL`() {
        assertEquals(
            "wss://popp.dev.poppservice.de/popp/practitioner/api/v1/token-generation-ehc",
            poppServiceUrlFor(Environment.DEV),
        )
    }

    @Test
    fun `host infix follows the environment`() {
        assertEquals("wss://popp.ref.poppservice.de$POPP_TOKEN_PATH", poppServiceUrlFor(Environment.REF))
        assertEquals("wss://popp.test.poppservice.de$POPP_TOKEN_PATH", poppServiceUrlFor(Environment.TEST))
        assertEquals("wss://popp.prod.poppservice.de$POPP_TOKEN_PATH", poppServiceUrlFor(Environment.PROD))
    }
}
