package de.gematik.zeta.cli.probe

import de.gematik.zeta.sdk.SdkStatus
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.authentication.AuthenticationStorage
import de.gematik.zeta.sdk.authentication.SubjectTokenProvider
import de.gematik.zeta.sdk.authentication.identity.ChangeEmailResponse
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.tpm.TpmProvider
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import java.lang.reflect.Proxy

/** In-memory [AuthenticationStorage] with the SDK's `at` / `rt` / `exp` semantics. */
internal class FakeAuth : AuthenticationStorage {
    var at: String? = null
    var rt: String? = null
    var exp: Long? = null

    override suspend fun saveAccessTokens(accessToken: String, refreshToken: String, expiresAt: Long) {
        at = accessToken; rt = refreshToken; exp = expiresAt
    }
    override suspend fun getAccessToken(): String? = at
    override suspend fun getRefreshToken(): String? = rt
    override suspend fun getTokenExpiration(): String? = exp?.toString()
    override suspend fun clearAccessToken() { at = null; exp = null }
    override suspend fun clear() { at = null; rt = null; exp = null }
}

internal val unusedTpm: TpmProvider = Proxy.newProxyInstance(
    TpmProvider::class.java.classLoader,
    arrayOf(TpmProvider::class.java),
) { _, m, _ -> error("TpmProvider.${m.name} is not expected in this test") } as TpmProvider

/**
 * A [ZetaSdkClient] that reproduces the parts of the real state machine the probes depend on:
 * `status()` from the stored tokens, `authenticate()` returning early on a valid token, refreshing when
 * a refresh token exists (falling back to a full exchange when [refreshFails]), and only the full
 * exchange asking the [provider] for a subject token.
 */
internal class FakeSdk(
    val auth: FakeAuth,
    private val provider: SubjectTokenProvider,
    var registered: Boolean = true,
    var refreshFails: Boolean = false,
    var exchangeFails: Boolean = false,
    var beforeAuthenticate: suspend () -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : ZetaSdkClient {
    var discoverCalls = 0
    var registerCalls = 0
    var refreshCalls = 0
    var exchangeCalls = 0
    private var counter = 0

    override suspend fun discover(): Result<Unit> = runCatching { discoverCalls++ }
    override suspend fun register(): Result<Unit> = runCatching { registerCalls++; registered = true }

    override suspend fun authenticate(): Result<Unit> = runCatching {
        beforeAuthenticate()
        val exp = auth.exp
        if (auth.at != null && exp != null && exp - now() > 10) return@runCatching
        if (!auth.rt.isNullOrBlank()) {
            refreshCalls++
            if (!refreshFails) {
                issue()
                return@runCatching
            }
        }
        exchangeCalls++
        if (exchangeFails) error("token endpoint said no")
        provider.createSubjectToken("client", "dpop", ByteArray(0), "aud", now(), now() + 30, unusedTpm)
        issue()
    }

    private suspend fun issue() {
        counter++
        auth.saveAccessTokens("at$counter", "rt$counter", now() + 300)
    }

    override suspend fun status(): Result<SdkStatus> = runCatching {
        if (!registered) return@runCatching SdkStatus.NOT_REGISTERED
        val expired = (auth.exp ?: 0L) <= now()
        when {
            !auth.at.isNullOrBlank() && !auth.rt.isNullOrBlank() && !expired -> SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN
            !auth.rt.isNullOrBlank() -> SdkStatus.HAS_REFRESH_TOKEN
            else -> SdkStatus.REGISTERED_NO_VALID_TOKENS
        }
    }

    override fun httpClient(builder: ZetaHttpClientBuilder.() -> Unit): ZetaHttpClient = error("unused")
    override suspend fun <R> ws(
        targetUrl: String,
        builder: ZetaHttpClientBuilder.() -> Unit,
        customHeaders: Map<String, String>?,
        block: suspend DefaultClientWebSocketSession.() -> R,
    ) = error("unused")
    override suspend fun logout(): Result<Unit> = Result.success(Unit)
    override suspend fun close(): Result<Unit> = Result.success(Unit)
    override suspend fun changeEmail(newEmail: String): Result<ChangeEmailResponse> = error("unused")
}

internal class FakeSigner : SubjectTokenProvider {
    var calls = 0
    var delayMs = 0L
    override suspend fun createSubjectToken(
        clientId: String, dpopKey: String, nonceBytes: ByteArray, audience: String, now: Long, expiration: Long, tpmProvider: TpmProvider,
    ): String {
        calls++
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
        return "subject-token-$calls"
    }
}

internal fun fakeSession(
    sdk: FakeSdk,
    provider: CountingSubjectTokenProvider,
    target: ProbeTarget = ProbeTarget("https://a/", listOf("s"), "a"),
) = TargetSession(target, sdk, sdk.auth, provider)
