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
package de.gematik.zeta.cli.probe

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProbeDurationTest {
    @Test
    fun `error type prefers the SDK flow code over the exception class`() {
        assertEquals("AUTHENTICATION_ERROR", errorTypeOf(IllegalStateException("[AUTHENTICATION_ERROR] Client hat keine Berechtigung")))
        assertEquals("REGISTRATION_FAILED_ERROR", errorTypeOf(IllegalStateException("[REGISTRATION_FAILED_ERROR] Client authentication failed")))
        assertEquals("IllegalStateException", errorTypeOf(IllegalStateException("token endpoint said no")))
        assertEquals("SocketException", errorTypeOf(java.net.SocketException("Connection reset")))
        assertEquals("RuntimeException", errorTypeOf(RuntimeException()))
    }

    @Test
    fun `accepts the compact forms and ISO`() {
        assertEquals(3.hours, parseProbeDuration("3h"))
        assertEquals(5.minutes, parseProbeDuration("5m"))
        assertEquals(90.seconds, parseProbeDuration("90s"))
        assertEquals(90.minutes, parseProbeDuration("1h30m"))
        assertEquals(3.hours, parseProbeDuration("PT3H"))
        assertEquals(Duration.ZERO, parseProbeDuration("0"))
        assertEquals(Duration.ZERO, parseProbeDuration("off"))
        assertEquals(Duration.ZERO, parseProbeDuration(" OFF "))
    }

    @Test
    fun `rejects garbage and negatives`() {
        assertThrows(IllegalArgumentException::class.java) { parseProbeDuration("abc") }
        assertThrows(IllegalArgumentException::class.java) { parseProbeDuration("-5m") }
        assertThrows(IllegalArgumentException::class.java) { parseProbeDuration("") }
    }
}
