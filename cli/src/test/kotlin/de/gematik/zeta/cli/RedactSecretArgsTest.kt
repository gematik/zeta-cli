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
import org.junit.jupiter.api.Test

class RedactSecretArgsTest {

    private fun redact(vararg args: String) = redactSecretArgs(arrayOf(*args))

    @Test
    fun `drops proxy password value in separate flag`() {
        assertEquals(
            listOf("http", "--proxy-password", "***"),
            redact("http", "--proxy-password", "s3cret"),
        )
    }

    @Test
    fun `drops proxy password value in equals form`() {
        assertEquals(listOf("--proxy-password=***"), redact("--proxy-password=s3cret"))
    }

    @Test
    fun `drops keystore password and popp token`() {
        assertEquals(
            listOf("--auth-p12-password", "***", "-p", "***"),
            redact("--auth-p12-password", "00", "-p", "eyToken"),
        )
    }

    @Test
    fun `masks only the password embedded in a proxy url`() {
        assertEquals(
            listOf("--proxy", "http://alice:***@proxy.example.com:8080"),
            redact("--proxy", "http://alice:s3cret@proxy.example.com:8080"),
        )
    }

    @Test
    fun `masks proxy url password in equals form`() {
        assertEquals(
            listOf("--proxy=http://alice:***@proxy:8080"),
            redact("--proxy=http://alice:s3cret@proxy:8080"),
        )
    }

    @Test
    fun `leaves credential-free proxy url intact`() {
        assertEquals(listOf("--proxy", "http://proxy:8080"), redact("--proxy", "http://proxy:8080"))
    }

    @Test
    fun `leaves username-only proxy url intact`() {
        assertEquals(
            listOf("--proxy", "http://alice@proxy:8080"),
            redact("--proxy", "http://alice@proxy:8080"),
        )
    }

    @Test
    fun `leaves non-secret args untouched`() {
        assertEquals(
            listOf("-vv", "http", "https://api.example.com", "--proxy-user", "alice"),
            redact("-vv", "http", "https://api.example.com", "--proxy-user", "alice"),
        )
    }

    @Test
    fun `trailing secret flag without a value is left as-is`() {
        assertEquals(listOf("http", "--proxy-password"), redact("http", "--proxy-password"))
    }
}
