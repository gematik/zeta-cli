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
package de.gematik.zeta.cli.serve

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.gematik.zeta.cli.popp.CardTransport
import de.gematik.zeta.cli.popp.PoppProtocolException
import de.gematik.zeta.cli.trace.Span
import de.gematik.zeta.cli.trace.Tracer
import com.github.ajalt.clikt.core.UsageError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.nio.file.Path

class DaemonTraceTest {
    private val delivered = mutableListOf<Span>()
    private val lock = Mutex()

    @BeforeEach
    fun enableTracer() = Tracer.initDaemon { delivered += it }

    @AfterEach
    fun resetTracer() = Tracer.reset()

    @Test
    fun `a successful mint is an ok event that measured the lock`() = runBlocking {
        val result = tracedMint(lock, egkHandleGiven = true) {
            assertTrue(lock.isLocked)
            MintResult.Ok("token")
        }

        assertEquals(MintResult.Ok("token"), result)
        assertFalse(lock.isLocked)
        val span = delivered.single()
        assertEquals("mint", span.name)
        assertTrue(span.ok)
        assertEquals(true, span.attrs["egkHandle"])
        assertEquals(listOf("lock.wait"), span.children.map { it.name })
    }

    @Test
    fun `a failed mint result marks the event failed without its message`() = runBlocking {
        val failed = MintResult.Failed(HttpStatusCode.BadGateway, "PoPP failed: kvnr=A123456789")

        val result = tracedMint(lock, egkHandleGiven = false) { failed }

        assertEquals(failed, result)
        val span = delivered.single()
        assertFalse(span.ok)
        assertEquals("MintFailed", span.errorType)
        assertEquals(502, span.attrs["status"])
    }

    @Test
    fun `a refresh event measures the lock and the authentication`() = runBlocking {
        val ok = refreshOnce(lock, "https://popp/", listOf("popp")) { assertTrue(lock.isLocked) }

        assertTrue(ok)
        val span = delivered.single()
        assertEquals("refresh", span.name)
        assertTrue(span.ok)
        assertEquals("https://popp/", span.attrs["resource"])
        assertEquals("popp", span.attrs["scopes"])
        assertEquals(listOf("lock.wait", "sdk.authenticate"), span.children.map { it.name })
    }

    @Test
    fun `a failed refresh returns false and marks the event`() = runBlocking {
        val ok = refreshOnce(lock, "https://popp/", listOf("popp")) { error("auth server down") }

        assertFalse(ok)
        assertFalse(lock.isLocked)
        val span = delivered.single()
        assertFalse(span.ok)
        assertEquals("IllegalStateException", span.errorType)
    }

    @Test
    fun `kartos without an image is a usage error`() {
        assertThrows(UsageError::class.java) { requireKartosImage(CardTransport.KARTOS, null) }
    }

    @Test
    fun `kartos with an image and other transports without one pass`() {
        requireKartosImage(CardTransport.KARTOS, Path.of("card.xml"))
        requireKartosImage(CardTransport.STANDARD, null)
        requireKartosImage(CardTransport.CONNECTOR, null)
    }

    @Test
    fun `a protocol error from the service reaches the daemon log`() = runBlocking {
        val logger = LoggerFactory.getLogger("de.gematik.zeta.cli.serve.VsdmProxy") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val result = mintResultOf { throw PoppProtocolException("SOME_CODE", "the service said no") }

            assertEquals(HttpStatusCode.BadGateway, (result as MintResult.Failed).status)
            assertTrue(appender.list.any { it.throwableProxy?.message?.contains("the service said no") == true })
        } finally {
            logger.detachAppender(appender)
        }
    }
}
