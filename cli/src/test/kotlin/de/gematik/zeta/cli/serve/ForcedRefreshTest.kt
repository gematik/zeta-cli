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

import de.gematik.zeta.sdk.authentication.AuthenticationStorage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ForcedRefreshTest {
    private class FakeStorage : AuthenticationStorage {
        var access: String? = "old-access"
        var refresh: String? = "old-refresh"
        var expiration: String? = "1000"

        override suspend fun saveAccessTokens(accessToken: String, refreshToken: String, expiresAt: Long) {
            access = accessToken
            refresh = refreshToken
            expiration = expiresAt.toString()
        }

        override suspend fun getAccessToken() = access
        override suspend fun getRefreshToken() = refresh
        override suspend fun getTokenExpiration() = expiration
        override suspend fun clearAccessToken() {
            access = null
        }

        override suspend fun clear() {
            access = null
            refresh = null
            expiration = null
        }
    }

    /** What the SDK's getValidToken does: a stored token with time left is returned without any request. */
    private suspend fun sdkAuthenticate(storage: FakeStorage) {
        if (storage.getAccessToken() == null) storage.saveAccessTokens("new-access", "new-refresh", 2000)
    }

    @Test
    fun `refreshes although the stored token still has time left`() = runBlocking {
        val storage = FakeStorage()

        forceTokenRefresh(storage) { sdkAuthenticate(storage) }

        assertEquals("new-access", storage.access)
        assertEquals("2000", storage.expiration)
    }

    @Test
    fun `a failed refresh keeps the token that is still valid`() {
        val storage = FakeStorage()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { forceTokenRefresh(storage) { error("auth server down") } }
        }

        assertEquals("old-access", storage.access)
        assertEquals("old-refresh", storage.refresh)
        assertEquals("1000", storage.expiration)
    }

    @Test
    fun `without a stored token it only authenticates`() = runBlocking {
        val storage = FakeStorage().apply { clear() }

        forceTokenRefresh(storage) { sdkAuthenticate(storage) }

        assertEquals("new-access", storage.access)
    }
}
