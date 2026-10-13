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
