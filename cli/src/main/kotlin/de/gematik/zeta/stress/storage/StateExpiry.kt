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
package de.gematik.zeta.stress.storage

import de.gematik.zeta.stress.db.Db

/**
 * Simulates credential expiry by deleting the relevant rows from a client's `sdk_state`
 * namespace, leaving the DCR registration and TPM client key intact so the client stays
 * `REGISTERED_NO_VALID_TOKENS` (i.e. it must re-`authenticate()` but not re-`register()`).
 *
 * The key names/prefixes below match the SDK's domain stores as of zeta-sdk 1.2.2:
 * - tokens: `at:<h>` / `rt:<h>` / `exp:<h>` — the token-value keys keep their literal prefix
 *   (they are written outside the resource-scope hashing). The `auth_token_index` map is itself a
 *   bare hash and is left in place; nulling the value keys is enough to invalidate the tokens.
 * - ASL session: `asl_session_by_resource:<h>` — likewise prefix-preserved; its `asl_session_index`
 *   map is a bare hash, left inert.
 * where `<h>` is an 8-char hash. `expireEverything` (register-storm) is layout-agnostic. Confirm
 * against a live 1.2.2 `sdk_state` dump before relying on the patterns.
 */
class StateExpiry(private val db: Db) {

    /** Drop access + refresh tokens: forces a full token exchange. */
    fun expireTokens(clientRef: String) = deleteWhere(
        clientRef,
        "key LIKE 'at:%' OR key LIKE 'rt:%' OR key LIKE 'exp:%'",
    )

    /** Drop only the access token, keeping the refresh token: exercises the refresh path. */
    fun expireAccessTokenOnly(clientRef: String) = deleteWhere(
        clientRef,
        "key LIKE 'at:%' OR key LIKE 'exp:%'",
    )

    /** Drop the established ASL session: forces a fresh ASL handshake on the next request. */
    fun expireAsl(clientRef: String) = deleteWhere(
        clientRef,
        "key LIKE 'asl_%'",
    )

    /** The thundering-herd precondition: tokens and ASL both gone, registration retained. */
    fun expireAll(clientRef: String) {
        expireTokens(clientRef)
        expireAsl(clientRef)
    }

    /**
     * Full cold reset: drop *every* `sdk_state` row for the client — DCR registration, client key,
     * tokens, ASL, and the cached discovery metadata — so the next attempt re-runs the whole cold
     * cycle (discovery → nonce → DCR → token exchange). Used by `register-storm` to keep client
     * registration under load; note each attempt then creates a fresh server-side client.
     */
    fun expireEverything(clientRef: String) = deleteWhere(clientRef, "1 = 1")

    /** How many `sdk_state` rows this client holds whose key matches [likePattern] (SQL LIKE). */
    fun countKeys(clientRef: String, likePattern: String): Int = db.withConnection { c ->
        c.prepareStatement("SELECT count(*) FROM sdk_state WHERE client_ref = ? AND key LIKE ?").use { ps ->
            ps.setString(1, clientRef)
            ps.setString(2, likePattern)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    private fun deleteWhere(clientRef: String, predicate: String) = db.withConnection { c ->
        c.prepareStatement("DELETE FROM sdk_state WHERE client_ref = ? AND ($predicate)").use { ps ->
            ps.setString(1, clientRef)
            ps.executeUpdate()
        }
    }
}
