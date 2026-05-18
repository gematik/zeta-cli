package de.gematik.zeta.cli.trace

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

private val log = KotlinLogging.logger("de.gematik.zeta.trace")
private val currentSpanTL = ThreadLocal<Span?>()
private val spanIdSeq = AtomicLong(0)

/**
 * In-process trace span. Lightweight, no thread-safety on attrs/children — spans are
 * mutated only on the thread that opened them. Children-list mutation happens at `init`
 * under the parent's logical ownership; a span's children are read only after the root
 * is closed (in [Tracer.emit]), at which point everything is happens-before via the
 * root's `end()`.
 *
 * The single [NoopSpan] instance is handed back to callers when tracing is disabled, so
 * `Tracer.span(...) { … }` allocates zero objects on the disabled fast path.
 */
open class Span internal constructor(
    val name: String,
    val parent: Span?,
    val startNanos: Long = System.nanoTime(),
) {
    val id: Long = spanIdSeq.incrementAndGet()
    val attrs: MutableMap<String, Any?> = LinkedHashMap()
    val children: MutableList<Span> = ArrayList()
    var endNanos: Long = -1L
        private set
    var ok: Boolean = true
        private set
    var error: String? = null
        private set

    init {
        if (this !is NoopSpan) parent?.children?.add(this)
    }

    open fun attr(key: String, value: Any?): Span {
        attrs[key] = value
        return this
    }

    open fun fail(message: String?) {
        ok = false
        error = message
    }

    internal open fun end() {
        if (endNanos < 0) endNanos = System.nanoTime()
    }

    /** Wall-clock duration in milliseconds. Negative until [end] has been called. */
    val durationMs: Double
        get() = if (endNanos < 0) -1.0 else (endNanos - startNanos) / 1_000_000.0
}

/** No-op span: every mutation is dropped, no children list churn, no parent linkage. */
internal object NoopSpan : Span(name = "<noop>", parent = null, startNanos = 0L) {
    override fun attr(key: String, value: Any?): Span = this
    override fun fail(message: String?) {}
    override fun end() {}
}

/**
 * Singleton tracer. Two modes:
 *
 * - **Disabled** (default): every [span]/[spanSuspend]/[spanUnder] call short-circuits to
 *   running [block] with [NoopSpan]. No allocation, no ThreadLocal churn. [emit] is a
 *   no-op.
 * - **Enabled** (after [init]): spans are collected into an in-memory tree rooted at
 *   [root]. [emit] renders the tree and writes it to the `de.gematik.zeta.trace` logger
 *   at INFO, so it always prints when `--trace` is on (independent of `-v`).
 */
object Tracer {
    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    var root: Span? = null
        private set

    /**
     * Switch the tracer on. Idempotent. Call once from [de.gematik.zeta.cli.main] when
     * `--trace` (or `ZETA_TRACE`) is set, before any [root] / [span] calls.
     */
    fun init() {
        enabled = true
    }

    /** Current span on this thread / coroutine, or `null` outside any root. */
    fun current(): Span? = if (enabled) currentSpanTL.get() else null

    /**
     * Open a root span and run [block] inside it. When disabled, just runs [block] with
     * [NoopSpan] — no root is installed. The root is left in place after [block] returns
     * so [emit] can render the tree.
     */
    fun <T> root(name: String, attrs: Map<String, Any?> = emptyMap(), block: (Span) -> T): T {
        if (!enabled) return block(NoopSpan)
        val span = Span(name, parent = null).apply { attrs.forEach { (k, v) -> attr(k, v) } }
        root = span
        return runIn(span, block)
    }

    /**
     * Open a child of [current] (or an orphan when no root is active) and run [block] in it.
     * Blocking variant — uses a ThreadLocal for parent lookup. Safe to call from inside a
     * `runBlocking { … }` because the coroutine runs on the calling thread by default.
     */
    fun <T> span(name: String, attrs: Map<String, Any?> = emptyMap(), block: (Span) -> T): T {
        if (!enabled) return block(NoopSpan)
        val span = Span(name, parent = currentSpanTL.get()).apply { attrs.forEach { (k, v) -> attr(k, v) } }
        return runIn(span, block)
    }

    /**
     * Suspend variant of [span]. Uses [asContextElement] to restore the ThreadLocal on every
     * coroutine dispatch, so child spans opened deep inside the suspend block correctly
     * find this span as their parent even across dispatcher switches.
     */
    suspend fun <T> spanSuspend(
        name: String,
        attrs: Map<String, Any?> = emptyMap(),
        block: suspend (Span) -> T,
    ): T {
        if (!enabled) return block(NoopSpan)
        val span = Span(name, parent = currentSpanTL.get()).apply { attrs.forEach { (k, v) -> attr(k, v) } }
        return try {
            withContext(currentSpanTL.asContextElement(span)) { block(span) }
        } catch (t: Throwable) {
            span.fail(t.message ?: t::class.simpleName)
            throw t
        } finally {
            span.end()
        }
    }

    /**
     * Open a span as a child of [parent] explicitly, bypassing the ThreadLocal lookup.
     * Used for the popp WS message spans, which we want as siblings of `popp.connect`
     * (i.e. children of the surrounding `sdk.session`) rather than nested inside the
     * long-lived connect span.
     */
    suspend fun <T> spanUnder(
        parent: Span,
        name: String,
        attrs: Map<String, Any?> = emptyMap(),
        block: suspend (Span) -> T,
    ): T {
        if (!enabled) return block(NoopSpan)
        val span = Span(name, parent = parent).apply { attrs.forEach { (k, v) -> attr(k, v) } }
        return try {
            withContext(currentSpanTL.asContextElement(span)) { block(span) }
        } catch (t: Throwable) {
            span.fail(t.message ?: t::class.simpleName)
            throw t
        } finally {
            span.end()
        }
    }

    /**
     * Render the collected tree and emit it as a single `log.info` line on
     * `de.gematik.zeta.trace`. The trace logger's level is INFO in `logback.xml`, so the
     * tree always prints when [enabled] — independent of the `-v` count. No-op when
     * tracing is disabled or no root was opened.
     */
    fun emit() {
        if (!enabled) return
        val r = root ?: return
        log.info { "\n" + renderSpanTree(r) }
    }

    /** Test-only reset of all state. */
    internal fun reset() {
        enabled = false
        root = null
        currentSpanTL.remove()
    }

    private inline fun <T> runIn(span: Span, block: (Span) -> T): T {
        val prev = currentSpanTL.get()
        currentSpanTL.set(span)
        return try {
            block(span)
        } catch (t: Throwable) {
            span.fail(t.message ?: t::class.simpleName)
            throw t
        } finally {
            span.end()
            currentSpanTL.set(prev)
        }
    }
}
