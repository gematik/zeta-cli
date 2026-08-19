package de.gematik.zeta.cli.client

import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay

private val log = KotlinLogging.logger {}

private const val ASL_RETRY_BACKOFF_MS = 150L

/**
 * Workaround for an SDK ASL bug: when the server's ASL session has expired, the SDK's internal retry
 * re-wraps an already-encrypted body, so the PEP answers with a spurious `406 application/cbor` (or the
 * call throws with `cbor` in the message) instead of the real response. A *fresh* request re-handshakes
 * the ASL session cleanly, so re-issue [request] up to [maxAttempts] times whenever the result looks like
 * that glitch. Only ever wrap **idempotent** operations (VSD reads are GETs) — never a PoPP mint.
 *
 * Remove once the SDK's idempotent-retry fix ships (branch `fix/asl-idempotent-retry` off zeta-sdk 1.2.5).
 */
internal suspend fun withAslExpiryRetry(
    maxAttempts: Int = 3,
    request: suspend () -> ZetaHttpResponse,
): ZetaHttpResponse {
    lateinit var response: ZetaHttpResponse
    for (attempt in 1..maxAttempts) {
        try {
            response = request()
        } catch (e: Exception) {
            if (attempt < maxAttempts && looksLikeAslExpiry(e)) {
                log.warn { "ASL session expired (attempt $attempt/$maxAttempts): ${e.message}; re-handshaking" }
                delay(ASL_RETRY_BACKOFF_MS)
                continue
            }
            throw e
        }
        if (attempt < maxAttempts && looksLikeAslExpiry(response)) {
            log.warn { "ASL session expired — 406 application/cbor (attempt $attempt/$maxAttempts); re-handshaking" }
            delay(ASL_RETRY_BACKOFF_MS)
            continue
        }
        return response
    }
    return response
}

/**
 * A `406` that arrives as a plain (non-ASL) response, or carries an `application/cbor` content type, is
 * the PEP rejecting a stale ASL session — not a genuine VSDM "Not Acceptable" (which comes back
 * ASL-encrypted and decoded).
 */
private fun looksLikeAslExpiry(response: ZetaHttpResponse): Boolean {
    if (response.status.value != 406) return false
    if (response.isPlainResponse()) return true
    return response.headers.entries.any { it.key.equals("Content-Type", ignoreCase = true) && it.value.contains("cbor", ignoreCase = true) }
}

private fun looksLikeAslExpiry(e: Throwable): Boolean =
    (e.message ?: "").contains("cbor", ignoreCase = true)
