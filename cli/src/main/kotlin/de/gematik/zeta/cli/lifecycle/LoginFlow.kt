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
