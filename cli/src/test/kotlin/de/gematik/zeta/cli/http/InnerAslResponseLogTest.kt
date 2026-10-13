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
package de.gematik.zeta.cli.http

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InnerAslResponseLogTest {
    @Test
    fun innerAslResponseWireDump_isParsedAsAResponseWithBody() {
        val dump = innerAslResponseWireDump(
            status = 200,
            reason = "OK",
            headers = mapOf("Content-Type" to "application/fhir+json", "Content-Length" to "27"),
            body = """{"resourceType":"Bundle"}""".toByteArray(),
        )

        // Fed to the same reformatter as the SDK's wire dumps, it renders a Response section
        // (not a Request) with the decoded headers and body.
        val rendered = reformatHttpLog(dump)

        listOf("Response", "200 OK", "Content-Type: application/fhir+json", "resourceType").forEach {
            assertTrue(rendered.contains(it), "expected rendered wire log to contain '$it', was:\n$rendered")
        }
    }
}
