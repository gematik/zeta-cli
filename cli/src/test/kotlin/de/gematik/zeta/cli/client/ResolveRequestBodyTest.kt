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
package de.gematik.zeta.cli.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class ResolveRequestBodyTest {

    @Test
    fun `literal value is sent as is`() {
        assertEquals("""{"a":1}""", resolveRequestBody("""{"a":1}"""))
    }

    @Test
    fun `at-file reads the file verbatim`(@TempDir dir: Path) {
        val content = "{\"p\": \"C:\\\\tmp\", \"q\": \"it's\"}\n" + "x".repeat(300_000)
        val file = dir.resolve("body.json").apply { writeText(content) }
        assertEquals(content, resolveRequestBody("@$file"))
    }

    @Test
    fun `at-dash reads stdin`() {
        assertEquals("line1\nline2\n", resolveRequestBody("@-", "line1\nline2\n".byteInputStream()))
    }
}
