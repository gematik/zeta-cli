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
