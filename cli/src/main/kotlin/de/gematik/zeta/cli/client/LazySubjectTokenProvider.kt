package de.gematik.zeta.cli.client

import de.gematik.zeta.sdk.authentication.SubjectTokenProvider
import de.gematik.zeta.sdk.tpm.TpmProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wraps a [factory] that builds the real [SubjectTokenProvider] only when
 * [createSubjectToken] is actually invoked — and at most once per process. The Zeta SDK
 * may skip this call entirely when its access/refresh tokens are still valid; in that
 * case nothing in [factory] runs, which is the whole point.
 *
 * The factory is expected to do whatever is expensive: connecting to the Connector,
 * enumerating cards, reading certs. A coroutine [Mutex] makes the init single-shot under
 * concurrent SDK auth attempts (refresh/retry races).
 */
internal class LazySubjectTokenProvider(
    private val factory: suspend () -> SubjectTokenProvider,
) : SubjectTokenProvider {
    private val mutex = Mutex()

    @Volatile
    private var delegate: SubjectTokenProvider? = null

    override suspend fun createSubjectToken(
        clientId: String,
        dpopKey: String,
        nonceBytes: ByteArray,
        audience: String,
        now: Long,
        expiration: Long,
        tpmProvider: TpmProvider,
    ): String =
        ensureDelegate().createSubjectToken(
            clientId,
            dpopKey,
            nonceBytes,
            audience,
            now,
            expiration,
            tpmProvider,
        )

    private suspend fun ensureDelegate(): SubjectTokenProvider =
        delegate ?: mutex.withLock { delegate ?: factory().also { delegate = it } }
}
