package de.gematik.zeta.cli.probe

import de.gematik.zeta.sdk.SdkStatus
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.ZetaSdkClientExtension
import de.gematik.zeta.sdk.authentication.AuthenticationStorage
import de.gematik.zeta.sdk.authentication.SubjectTokenProvider
import de.gematik.zeta.sdk.tpm.TpmProvider
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

internal enum class ProbeKind(val label: String) {
    /** Force a full token exchange: drop both tokens, then `authenticate()` (SMC-B signs a subject token). */
    LOGIN("login"),

    /** Force the refresh grant: drop only the access token, then `authenticate()`. */
    REFRESH("refresh"),
}

internal enum class ProbeResult(val label: String) { OK("ok"), ERROR("error"), TIMEOUT("timeout") }

internal data class StepTiming(val step: String, val duration: Duration, val ok: Boolean)

internal data class ProbeOutcome(
    val kind: ProbeKind,
    val target: ProbeTarget,
    val result: ProbeResult,
    val preStatus: SdkStatus?,
    val postStatus: SdkStatus?,
    /** A `refresh` probe that ended up doing a full exchange (the SDK falls back silently when the grant fails). */
    val fallback: Boolean,
    /** The one-time bootstrap (`discover` + `register`) ran in this probe. */
    val registered: Boolean,
    val errorType: String?,
    val errorMessage: String?,
    val steps: List<StepTiming>,
    val total: Duration,
    val tokenExpiryEpochSec: Long?,
)

/** Per-probe span hooks; [NoopSpanScope] for tests, the OTel-backed one in [ProbeTelemetry]. */
internal interface ProbeSpanScope {
    suspend fun <T> step(name: String, block: suspend () -> T): T
    fun end(outcome: ProbeOutcome)
}

internal object NoopSpanScope : ProbeSpanScope {
    override suspend fun <T> step(name: String, block: suspend () -> T): T = block()
    override fun end(outcome: ProbeOutcome) = Unit
}

internal data class SigningCall(val duration: Duration, val ok: Boolean)

/**
 * Wraps the CLI's [SubjectTokenProvider] so a probe can see whether the SDK asked for a subject token.
 * Only the full exchange does; the refresh grant never does. That makes it the one reliable signal for
 * "the refresh flow silently fell back to a login", and it gives the SMC-B signing leg its own timing.
 * [signingLock] serialises signing across targets when one Konnektor session backs them all.
 */
internal class CountingSubjectTokenProvider(
    private val delegate: SubjectTokenProvider,
    private val signingLock: Mutex? = null,
) : SubjectTokenProvider {
    private val calls = ConcurrentLinkedQueue<SigningCall>()

    /** The span scope of the probe currently running on this target; set under the target mutex. */
    @Volatile
    var spans: ProbeSpanScope = NoopSpanScope

    fun takeCalls(): List<SigningCall> = calls.toList().also { calls.clear() }

    override suspend fun createSubjectToken(
        clientId: String,
        dpopKey: String,
        nonceBytes: ByteArray,
        audience: String,
        now: Long,
        expiration: Long,
        tpmProvider: TpmProvider,
    ): String {
        val mark = TimeSource.Monotonic.markNow()
        var ok = false
        try {
            return spans.step(STEP_SUBJECT_TOKEN) {
                val sign: suspend () -> String = {
                    delegate.createSubjectToken(clientId, dpopKey, nonceBytes, audience, now, expiration, tpmProvider)
                }
                if (signingLock != null) signingLock.withLock { sign() } else sign()
            }.also { ok = true }
        } finally {
            calls += SigningCall(mark.elapsedNow(), ok)
        }
    }
}

/** One target's long-lived SDK client plus the handles a probe needs around it. */
internal class TargetSession(
    val target: ProbeTarget,
    val sdk: ZetaSdkClient,
    val auth: AuthenticationStorage,
    val subjectTokens: CountingSubjectTokenProvider,
    val mutex: Mutex = Mutex(),
) : Closeable {
    override fun close() {
        ZetaSdkClientExtension.close(sdk)
    }
}

internal const val STEP_STATUS = "status"
internal const val STEP_REGISTER = "register"
internal const val STEP_CLEAR = "clear"
internal const val STEP_AUTHENTICATE = "authenticate"
internal const val STEP_SUBJECT_TOKEN = "subject_token"
internal const val STEP_VERIFY = "verify"
internal const val STEP_TOTAL = "total"

private class ProbeVerifyException(val code: String, message: String) : RuntimeException(message)

private val SDK_ERROR_CODE = Regex("""^\[([A-Z_]+)]""")

/**
 * A bounded label for a failure: the SDK reports its flow errors as `IllegalStateException("[CODE] …")`,
 * and that code (`AUTHENTICATION_ERROR`, `REGISTRATION_FAILED_ERROR`, …) says far more than the class
 * name; everything else is labelled by exception class.
 */
internal fun errorTypeOf(error: Throwable): String =
    error.message?.let { SDK_ERROR_CODE.find(it)?.groupValues?.get(1) } ?: error::class.simpleName ?: "Throwable"

/**
 * Run one probe against one target, serialised per target so `login` and `refresh` never interleave on
 * the same token store. Every step is timed and recorded even when it fails; the clears in [STEP_CLEAR]
 * are load-bearing — with a valid access token stored, `authenticate()` returns without any network I/O.
 */
internal suspend fun runProbe(
    kind: ProbeKind,
    session: TargetSession,
    timeout: Duration,
    spans: ProbeSpanScope = NoopSpanScope,
): ProbeOutcome {
    val steps = mutableListOf<StepTiming>()
    var preStatus: SdkStatus? = null
    var postStatus: SdkStatus? = null
    var registered = false
    var expiry: Long? = null
    var signing = emptyList<SigningCall>()
    val start = TimeSource.Monotonic.markNow()

    suspend fun <T> step(name: String, block: suspend () -> T): T {
        val mark = TimeSource.Monotonic.markNow()
        var ok = false
        try {
            return spans.step(name) { block() }.also { ok = true }
        } finally {
            steps += StepTiming(name, mark.elapsedNow(), ok)
        }
    }

    val error: Throwable? = session.mutex.withLock {
        session.subjectTokens.takeCalls()
        session.subjectTokens.spans = spans
        try {
            withTimeout(timeout) {
                val initial = step(STEP_STATUS) { session.sdk.status().getOrThrow() }
                if (initial == SdkStatus.NOT_REGISTERED) {
                    step(STEP_REGISTER) {
                        session.sdk.discover().getOrThrow()
                        session.sdk.register().getOrThrow()
                    }
                    registered = true
                }
                step(STEP_CLEAR) {
                    when (kind) {
                        ProbeKind.LOGIN -> session.auth.clear()
                        ProbeKind.REFRESH -> session.auth.clearAccessToken()
                    }
                    preStatus = session.sdk.status().getOrThrow()
                }
                try {
                    step(STEP_AUTHENTICATE) { session.sdk.authenticate().getOrThrow() }
                } finally {
                    signing = session.subjectTokens.takeCalls()
                }
                step(STEP_VERIFY) {
                    val status = session.sdk.status().getOrThrow()
                    postStatus = status
                    if (status != SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN) {
                        throw ProbeVerifyException("no_tokens", "status after authenticate is $status")
                    }
                    expiry = session.auth.getTokenExpiration()?.toLongOrNull()
                }
            }
            null
        } catch (e: TimeoutCancellationException) {
            e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e
        } finally {
            session.subjectTokens.spans = NoopSpanScope
        }
    }

    signing.forEach { steps += StepTiming(STEP_SUBJECT_TOKEN, it.duration, it.ok) }
    val outcome = ProbeOutcome(
        kind = kind,
        target = session.target,
        result = when (error) {
            null -> ProbeResult.OK
            is TimeoutCancellationException -> ProbeResult.TIMEOUT
            else -> ProbeResult.ERROR
        },
        preStatus = preStatus,
        postStatus = postStatus,
        fallback = kind == ProbeKind.REFRESH && signing.isNotEmpty(),
        registered = registered,
        errorType = when (error) {
            null -> null
            is TimeoutCancellationException -> "timeout"
            is ProbeVerifyException -> error.code
            else -> errorTypeOf(error)
        },
        errorMessage = error?.message,
        steps = steps,
        total = start.elapsedNow(),
        tokenExpiryEpochSec = expiry,
    )
    spans.end(outcome)
    return outcome
}
