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
