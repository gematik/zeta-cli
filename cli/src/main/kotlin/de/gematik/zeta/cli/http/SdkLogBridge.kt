package de.gematik.zeta.cli.http

import de.gematik.zeta.logging.Log
import de.gematik.zeta.logging.ZetaLogLevel
import de.gematik.zeta.logging.ZetaLogger
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

private val wireLog = KotlinLogging.logger("de.gematik.zeta.http.wire")

/**
 * Bridges the SDK's [Log] singleton into the CLI's Logback setup.
 *
 * Replaces the previous `ZetaHttpClientBuilder.logging(level, WireLogger)` hook, which became
 * `internal` in the new SDK. The SDK's default [io.ktor.client.plugins.logging.Logger]
 * (in `MonitoringConfig`) now funnels Ktor wire dumps through `Log.i { message }`, so we
 * install a `ZetaLogger` once at startup and route from there:
 *
 *  - INFO calls whose message starts with `REQUEST:` / `RESPONSE:` are treated as Ktor wire
 *    dumps. We pass them through [reformatHttpLog] and emit at DEBUG on
 *    `de.gematik.zeta.http.wire` — exactly what [WireLogger] used to do for the SDK's HTTP
 *    clients, and what [installCurlieLogging] still does for the CLI's own Ktor clients.
 *  - Everything else routes to `de.gematik.zeta.sdk[.<tag>]` at the matching SLF4J level.
 *
 * Tag handling: the SDK passes `null` for most call sites, so the default logger name is
 * `de.gematik.zeta.sdk`. Logback can target it via `<logger name="de.gematik.zeta.sdk" .../>`.
 */
object SdkLogBridge : ZetaLogger {
    override fun d(tag: String?, message: () -> String, throwable: Throwable?) {
        loggerFor(tag).debug(throwable) { message() }
    }

    override fun i(tag: String?, message: () -> String, throwable: Throwable?) {
        val text = message()
        if (isKtorWireDump(text)) {
            wireLog.debug(throwable) { reformatHttpLog(text) }
        } else {
            loggerFor(tag).info(throwable) { text }
        }
    }

    override fun w(tag: String?, message: () -> String, throwable: Throwable?) {
        loggerFor(tag).warn(throwable) { message() }
    }

    override fun e(tag: String?, message: () -> String, throwable: Throwable?) {
        loggerFor(tag).error(throwable) { message() }
    }
}

/**
 * Install [SdkLogBridge] as the global SDK log destination, and lift the SDK's level gate so
 * SLF4J/Logback (the downstream level filter) actually decides what fires.
 *
 * `Log.setLogger` only installs the destination — `Log.setLogLevel` controls the SDK's own
 * gate (default `ERROR`). Without bumping it, INFO/DEBUG SDK calls (including the wire-dump
 * funnel `Log.i { message }`) get dropped before our bridge sees them.
 */
fun installSdkLogBridge() {
    Log.setLogger(SdkLogBridge)
    Log.setLogLevel(ZetaLogLevel.DEBUG)
}

// Ktor's `Logging` plugin prefixes its multi-line dumps with `REQUEST:` or `RESPONSE:`.
// Sniffing the message head keeps the bridge SDK-independent: when the SDK does internal
// `Log.i { "... " }` calls, those don't start with these markers and pass through unchanged.
private fun isKtorWireDump(message: String): Boolean =
    message.startsWith("REQUEST: ") || message.startsWith("RESPONSE: ")

private fun loggerFor(tag: String?): KLogger {
    val name = if (tag.isNullOrBlank()) "de.gematik.zeta.sdk" else "de.gematik.zeta.sdk.$tag"
    return KotlinLogging.logger(name)
}
