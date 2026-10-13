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
package de.gematik.zeta.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ArgvSniffTest {

    @Test
    fun `recognises -f VALUE`() {
        assertEquals("x.yaml", sniffOptValue(arrayOf("-f", "x.yaml"), "-f", "--file"))
    }

    @Test
    fun `recognises --file VALUE`() {
        assertEquals("x.yaml", sniffOptValue(arrayOf("--file", "x.yaml"), "-f", "--file"))
    }

    @Test
    fun `recognises --file=VALUE`() {
        assertEquals("x.yaml", sniffOptValue(arrayOf("--file=x.yaml"), "-f", "--file"))
    }

    @Test
    fun `recognises -f=VALUE`() {
        assertEquals("x.yaml", sniffOptValue(arrayOf("-f=x.yaml"), "-f", "--file"))
    }

    @Test
    fun `trailing -f without value returns null`() {
        assertNull(sniffOptValue(arrayOf("-f"), "-f", "--file"))
    }

    @Test
    fun `empty argv returns null`() {
        assertNull(sniffOptValue(arrayOf(), "-f", "--file"))
    }

    @Test
    fun `first match wins on duplicates`() {
        assertEquals(
            "x.yaml",
            sniffOptValue(arrayOf("-f", "x.yaml", "--file", "y.yaml"), "-f", "--file"),
        )
    }

    @Test
    fun `ignores unrelated args around the match`() {
        assertEquals(
            "x.yaml",
            sniffOptValue(arrayOf("status", "-vv", "--file", "x.yaml", "URL"), "-f", "--file"),
        )
    }

    @Test
    fun `does not match an option whose name is a prefix of an arg`() {
        // "--filey" is not "--file" — must not be consumed.
        assertNull(sniffOptValue(arrayOf("--filey", "x.yaml"), "-f", "--file"))
    }
}
