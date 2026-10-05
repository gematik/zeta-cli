package de.gematik.zeta.cli.connector

import de.gematik.connector.Credentials
import de.gematik.connector.Dotkon
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DotkonRouteTest {
    private fun selector(vararg proxies: Proxy) = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = proxies.toList()
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
    }

    @Test
    fun `no proxy from the selector means direct`() {
        assertEquals("direct", jvmProxyRoute("https://konnektor.local", selector(Proxy.NO_PROXY)))
        assertEquals("direct", jvmProxyRoute("https://konnektor.local", selector()))
        assertEquals("direct", jvmProxyRoute("https://konnektor.local", null))
    }

    @Test
    fun `a selected proxy is named`() {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.intern", 8080))
        assertEquals("via proxy.intern:8080 (JVM proxy properties)", jvmProxyRoute("https://konnektor.local", selector(proxy)))
    }

    @Test
    fun `an unparsable url is reported as direct rather than failing`() {
        assertEquals("direct", jvmProxyRoute("not a url with spaces", selector()))
    }

    @Test
    fun `the kon client never carries the CLI proxy`() {
        val dotkon = Dotkon(url = "https://konnektor.local", credentials = Credentials.None)
        dotkonHttpClient(dotkon, 5.seconds, 30.seconds).use { client ->
            assertNull(client.engine.config.proxy)
        }
    }
}
