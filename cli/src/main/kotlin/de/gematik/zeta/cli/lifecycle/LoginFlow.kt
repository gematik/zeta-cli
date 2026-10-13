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
package de.gematik.zeta.cli.lifecycle

import de.gematik.zeta.cli.trace.Tracer
import de.gematik.zeta.sdk.SdkStatus
import de.gematik.zeta.sdk.ZetaSdkClient

/**
 * Idempotent "make this resource usable": `discover()` + `register()` only when the client is
 * `NOT_REGISTERED`, and `authenticate()` unless a valid access+refresh token is already cached.
 * Returns `(ranRegister, ranAuthenticate)`. Shared by `zeta login` and `zeta serve`'s warm-up so the
 * two stay in lockstep. Suspends — callers on a blocking thread wrap it in `runBlocking`.
 */
internal suspend fun ensureLoggedIn(sdk: ZetaSdkClient): Pair<Boolean, Boolean> {
    val initial = sdk.status().getOrThrow()

    val ranRegister = initial == SdkStatus.NOT_REGISTERED
    if (ranRegister) {
        // register() requires discover()-populated AS metadata; discover() short-circuits if cached.
        sdk.discover().getOrThrow()
        sdk.register().getOrThrow()
    }

    val ranAuthenticate = initial != SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN
    if (ranAuthenticate) {
        Tracer.spanSuspend("sdk.authenticate") { sdk.authenticate().getOrThrow() }
    }

    return ranRegister to ranAuthenticate
}
