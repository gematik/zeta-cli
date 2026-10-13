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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RefreshDelayTest {
    @Test
    fun `wakes lead-ms before expiry`() {
        // exp at 1000s, now at 900_000ms (=900s), lead 5s -> (1000_000 - 5000 - 900_000) = 95_000ms
        assertEquals(95_000L, refreshDelayMs(expSec = 1000, nowMs = 900_000, leadMs = 5_000))
    }

    @Test
    fun `floors at zero when the lead window has already passed`() {
        // now is inside the lead window (exp-lead already reached) -> 0, refresh immediately
        assertEquals(0L, refreshDelayMs(expSec = 1000, nowMs = 999_000, leadMs = 5_000))
    }

    @Test
    fun `floors at zero when the token is already expired`() {
        assertEquals(0L, refreshDelayMs(expSec = 1000, nowMs = 2_000_000, leadMs = 5_000))
    }
}
