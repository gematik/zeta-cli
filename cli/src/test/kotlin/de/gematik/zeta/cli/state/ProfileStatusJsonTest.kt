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
package de.gematik.zeta.cli.state

import de.gematik.zeta.sdk.SdkStatus
import java.nio.file.Path
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProfileStatusJsonTest {
    @Test
    fun `wraps profile, path and one resource per entry`() {
        val entries = listOf(
            Entry("https://a.example/", null, SdkStatus.NOT_REGISTERED, null, null),
            Entry("https://b.example/", "https://b.example/auth", SdkStatus.REGISTERED_NO_VALID_TOKENS, null, null),
        )

        val json = profileStatusJson("default", Path.of("/tmp/x.storage.db"), entries)

        assertEquals("default", json["profile"]!!.jsonPrimitive.content)
        assertEquals("/tmp/x.storage.db", json["path"]!!.jsonPrimitive.content)
        val resources = json["resources"]!!.jsonArray
        assertEquals(2, resources.size)
        assertEquals("https://a.example/", resources[0].jsonObject["resource"]!!.jsonPrimitive.content)
        assertEquals("NOT_REGISTERED", resources[0].jsonObject["status"]!!.jsonPrimitive.content)
    }
}
