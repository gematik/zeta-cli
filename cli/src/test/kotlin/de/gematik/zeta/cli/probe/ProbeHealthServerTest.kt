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

import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProbeHealthServerTest {
    private val client = HttpClient.newHttpClient()

    private fun get(port: Int, path: String, method: String = "GET"): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).method(method, HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `liveness is always ok and readiness follows the reason`() {
        var reason: String? = "starting"
        ProbeHealthServer("127.0.0.1", 0, notReadyReason = { reason }).start().use { server ->
            val port = server.port
            assertEquals(200, get(port, "/healthz").statusCode())
            assertEquals("ok\n", get(port, "/healthz").body())

            val notReady = get(port, "/readyz")
            assertEquals(503, notReady.statusCode())
            assertEquals("starting\n", notReady.body())
            assertEquals("text/plain; charset=utf-8", notReady.headers().firstValue("Content-Type").get())

            reason = null
            assertEquals(200, get(port, "/readyz").statusCode())
            assertEquals("ready\n", get(port, "/readyz").body())

            reason = "no targets"
            assertEquals("no targets\n", get(port, "/readyz").body())

            assertEquals(405, get(port, "/healthz", "POST").statusCode())
            assertEquals(404, get(port, "/metrics").statusCode())
        }
    }

    @Test
    fun `a failing readiness check is reported, not thrown`() {
        ProbeHealthServer("127.0.0.1", 0, notReadyReason = { error("boom") }).start().use { server ->
            val r = get(server.port, "/readyz")
            assertEquals(503, r.statusCode())
            assertEquals("readiness check failed: boom\n", r.body())
        }
    }

    @Test
    fun `closing stops the server`() {
        val server = ProbeHealthServer("127.0.0.1", 0, notReadyReason = { null }).start()
        val port = server.port
        assertEquals(200, get(port, "/healthz").statusCode())
        server.close()
        assertThrows(ConnectException::class.java) { get(port, "/healthz") }
    }

    @Test
    fun `liveness reports the problem with 503`() {
        var problem: String? = null
        ProbeHealthServer("127.0.0.1", 0, notReadyReason = { null }, livenessProblem = { problem }).start().use { server ->
            assertEquals(200, get(server.port, "/healthz").statusCode())
            problem = "refresh loop overdue by 95s"
            val r = get(server.port, "/healthz")
            assertEquals(503, r.statusCode())
            assertEquals("refresh loop overdue by 95s\n", r.body())
            assertEquals(200, get(server.port, "/readyz").statusCode())
        }
    }
}
