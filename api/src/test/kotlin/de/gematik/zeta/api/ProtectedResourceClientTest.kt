package de.gematik.zeta.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProtectedResourceClientTest {
    @Test
    fun `appends slash before well-known when baseUrl has none`() {
        assertEquals(
            "https://example.com/.well-known/oauth-protected-resource",
            ProtectedResourceClient.wellKnownUrl("https://example.com"),
        )
    }

    @Test
    fun `does not double slash when baseUrl ends with slash`() {
        assertEquals(
            "https://example.com/.well-known/oauth-protected-resource",
            ProtectedResourceClient.wellKnownUrl("https://example.com/"),
        )
    }

    @Test
    fun `preserves base path`() {
        assertEquals(
            "https://example.com/api/v1/.well-known/oauth-protected-resource",
            ProtectedResourceClient.wellKnownUrl("https://example.com/api/v1"),
        )
        assertEquals(
            "https://example.com/api/v1/.well-known/oauth-protected-resource",
            ProtectedResourceClient.wellKnownUrl("https://example.com/api/v1/"),
        )
    }

    @Test
    fun `fetches and parses metadata`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(
                "https://api.example.com/.well-known/oauth-protected-resource",
                request.url.toString(),
            )
            respond(
                content = """
                    {
                      "resource": "https://api.example.com",
                      "authorization_servers": ["https://as.example.com"],
                      "scopes_supported": ["read", "write"],
                      "unknown_extension_field": "ignored"
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = ProtectedResourceClient(httpClient = HttpClient(engine))
        val resource = client.fetch("https://api.example.com")
        assertEquals("https://api.example.com", resource.resource)
        assertEquals(listOf("https://as.example.com"), resource.authorizationServers)
        assertEquals(listOf("read", "write"), resource.scopesSupported)
    }

    @Test
    fun `wraps non-2xx responses in metadata exception`() = runBlocking {
        val engine = MockEngine { _ ->
            respond("not found", HttpStatusCode.NotFound)
        }
        val client = ProtectedResourceClient(httpClient = HttpClient(engine))
        assertThrows(ProtectedResourceMetadataException::class.java) {
            runBlocking { client.fetch("https://api.example.com") }
        }
    }
}
