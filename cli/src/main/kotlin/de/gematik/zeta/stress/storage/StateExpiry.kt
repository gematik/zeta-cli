package de.gematik.zeta.stress.storage

import de.gematik.zeta.stress.db.Db

/**
 * Simulates credential expiry by deleting the relevant rows from a client's `sdk_state`
 * namespace, leaving the DCR registration and TPM client key intact so the client stays
 * `REGISTERED_NO_VALID_TOKENS` (i.e. it must re-`authenticate()` but not re-`register()`).
 *
 * The key names/prefixes below match the SDK's domain stores as of zeta-sdk 1.2.0:
 * - tokens: `at:<h>` / `rt:<h>` / `exp:<h>` + the `hash_index` written by AuthenticationStorage
 * - ASL session: `asl_session_by_fqdn<h>` + `asl_hash_index_key`
 * where `<h>` is a per-resource hash. Confirm against a live run's `sdk_state` dump before
 * relying on the patterns (see the verification notes).
 */
class StateExpiry(private val db: Db) {

    /** Drop access + refresh tokens (and the token index): forces a full token exchange. */
    fun expireTokens(clientRef: String) = deleteWhere(
        clientRef,
        "key LIKE 'at:%' OR key LIKE 'rt:%' OR key LIKE 'exp:%' OR key = 'hash_index'",
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
