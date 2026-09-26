package de.gematik.zeta.cli.probe

import de.gematik.zeta.catalog.Environment
import de.gematik.zeta.sdk.SdkStatus
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProbesTest {
    private fun loggedIn(): Pair<FakeSdk, FakeSigner> {
        val signer = FakeSigner()
        val sdk = FakeSdk(FakeAuth(), signer)
        runBlocking { sdk.authenticate().getOrThrow() }
        assertEquals(1, signer.calls)
        return sdk to signer
    }

    private fun steps(o: ProbeOutcome) = o.steps.map { it.step }

    @Test
    fun `login clears both tokens and forces a full exchange`() = runBlocking {
        val (sdk, signer) = loggedIn()
        val counting = CountingSubjectTokenProvider(signer)
        val o = runProbe(ProbeKind.LOGIN, fakeSession(FakeSdk(sdk.auth, counting), counting), 10.seconds)

        assertEquals(ProbeResult.OK, o.result)
        assertEquals(SdkStatus.REGISTERED_NO_VALID_TOKENS, o.preStatus)
        assertEquals(SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN, o.postStatus)
        assertFalse(o.fallback)
        assertFalse(o.registered)
        assertEquals(2, signer.calls, "the login probe signed a second subject token")
        assertEquals(listOf(STEP_STATUS, STEP_CLEAR, STEP_AUTHENTICATE, STEP_VERIFY, STEP_SUBJECT_TOKEN), steps(o))
        assertTrue(o.steps.all { it.ok })
        assertNotNull(o.tokenExpiryEpochSec)
    }

    @Test
    fun `refresh keeps the refresh token and does not sign`() = runBlocking {
        val (sdk, signer) = loggedIn()
        val counting = CountingSubjectTokenProvider(signer)
        val fake = FakeSdk(sdk.auth, counting)
        val o = runProbe(ProbeKind.REFRESH, fakeSession(fake, counting), 10.seconds)

        assertEquals(ProbeResult.OK, o.result)
        assertEquals(SdkStatus.HAS_REFRESH_TOKEN, o.preStatus)
        assertFalse(o.fallback)
        assertEquals(1, signer.calls, "no subject token for a refresh grant")
        assertEquals(1, fake.refreshCalls)
        assertEquals(listOf(STEP_STATUS, STEP_CLEAR, STEP_AUTHENTICATE, STEP_VERIFY), steps(o))
    }

    @Test
    fun `refresh reports the silent fallback to a full exchange`() = runBlocking {
        val (sdk, signer) = loggedIn()
        val counting = CountingSubjectTokenProvider(signer)
        val fake = FakeSdk(sdk.auth, counting, refreshFails = true)
        val o = runProbe(ProbeKind.REFRESH, fakeSession(fake, counting), 10.seconds)

        assertEquals(ProbeResult.OK, o.result)
        assertTrue(o.fallback)
        assertEquals(2, signer.calls)
        assertTrue(STEP_SUBJECT_TOKEN in steps(o))
    }

    @Test
    fun `an unregistered target is bootstrapped first`() = runBlocking {
        val signer = FakeSigner()
        val counting = CountingSubjectTokenProvider(signer)
        val fake = FakeSdk(FakeAuth(), counting, registered = false)
        val o = runProbe(ProbeKind.REFRESH, fakeSession(fake, counting), 10.seconds)

        assertEquals(ProbeResult.OK, o.result)
        assertTrue(o.registered)
        assertEquals(1, fake.discoverCalls)
        assertEquals(1, fake.registerCalls)
        assertEquals(SdkStatus.REGISTERED_NO_VALID_TOKENS, o.preStatus)
        assertTrue(o.fallback, "no refresh token yet, so the first refresh probe is a full exchange")
        assertEquals(listOf(STEP_STATUS, STEP_REGISTER, STEP_CLEAR, STEP_AUTHENTICATE, STEP_VERIFY, STEP_SUBJECT_TOKEN), steps(o))
    }

    @Test
    fun `a failing exchange is an error with the exception class as type`() = runBlocking {
        val signer = FakeSigner()
        val counting = CountingSubjectTokenProvider(signer)
        val fake = FakeSdk(FakeAuth(), counting, exchangeFails = true)
        val o = runProbe(ProbeKind.LOGIN, fakeSession(fake, counting), 10.seconds)

        assertEquals(ProbeResult.ERROR, o.result)
        assertEquals("IllegalStateException", o.errorType)
        assertEquals("token endpoint said no", o.errorMessage)
        assertNull(o.postStatus)
        assertFalse(o.steps.first { it.step == STEP_AUTHENTICATE }.ok)
        assertEquals(listOf(STEP_STATUS, STEP_CLEAR, STEP_AUTHENTICATE), steps(o))
    }

    @Test
    fun `a stalled probe is a timeout`() = runBlocking {
        val signer = FakeSigner()
        val counting = CountingSubjectTokenProvider(signer)
        val fake = FakeSdk(FakeAuth(), counting, beforeAuthenticate = { kotlinx.coroutines.delay(5_000) })
        val o = runProbe(ProbeKind.LOGIN, fakeSession(fake, counting), 200.milliseconds)

        assertEquals(ProbeResult.TIMEOUT, o.result)
        assertEquals("timeout", o.errorType)
        assertFalse(o.steps.first { it.step == STEP_AUTHENTICATE }.ok)
    }

    @Test
    fun `verify fails when authenticate leaves no usable tokens`() = runBlocking {
        val counting = CountingSubjectTokenProvider(FakeSigner())
        val sdk = NoTokenSdk(FakeSdk(FakeAuth(), counting))
        val o = runProbe(ProbeKind.LOGIN, TargetSession(ProbeTarget(Environment.DEV, "https://a/", listOf("s"), "a", "a"), sdk, FakeAuth(), counting), 10.seconds)

        assertEquals(ProbeResult.ERROR, o.result)
        assertEquals("no_tokens", o.errorType)
        assertFalse(o.steps.first { it.step == STEP_VERIFY }.ok)
    }
}

/** An SDK whose `authenticate()` reports success without persisting any tokens. */
private class NoTokenSdk(private val inner: FakeSdk) : de.gematik.zeta.sdk.ZetaSdkClient by inner {
    override suspend fun authenticate(): Result<Unit> = Result.success(Unit)
    override suspend fun status(): Result<SdkStatus> = Result.success(SdkStatus.REGISTERED_NO_VALID_TOKENS)
}
