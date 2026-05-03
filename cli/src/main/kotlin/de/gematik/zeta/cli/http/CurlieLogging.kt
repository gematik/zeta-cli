package de.gematik.zeta.cli.http

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles
import de.gematik.zeta.cli.output.renderJson
import de.gematik.zeta.cli.output.renderXml
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import kotlinx.serialization.json.Json

private val wireLog = KotlinLogging.logger("de.gematik.zeta.http.wire")

private val SENSITIVE_HEADERS =
    setOf(
        "authorization",
        "cookie",
        "set-cookie",
        "proxy-authorization",
        "x-api-key",
    )

private val methodStyle: TextStyle = TextStyles.bold + TextColors.brightCyan
private val urlStyle: TextStyle = TextColors.green
private val protoStyle: TextStyle = TextColors.gray
private val headerNameStyle: TextStyle = TextColors.cyan

// Header values are intentionally unstyled — using the terminal's default foreground
// (rather than gray) keeps them at full readability while the coloured name carries the
// visual distinction. Matches what curlie/httpie do.
private val sectionRule: TextStyle = TextColors.gray
private val boldStyle: TextStyle = TextStyles.bold.style
private val statusOkStyle: TextStyle = TextStyles.bold + TextColors.green
private val statusRedirStyle: TextStyle = TextStyles.bold + TextColors.cyan
private val status4xxStyle: TextStyle = TextStyles.bold + TextColors.yellow
private val status5xxStyle: TextStyle = TextStyles.bold + TextColors.red

/**
 * Installs curlie-style request/response logging on a Ktor [HttpClient][io.ktor.client.HttpClient].
 *
 * Activated only when the `de.gematik.zeta.http.wire` SLF4J logger is at DEBUG (or finer) — i.e.
 * when the user passes `-vv` or higher. With it on, each request/response is rendered as:
 *
 * ```
 * ──── Request ────
 * GET https://api.example.com/foo HTTP/1.1
 * Accept: application/json
 * Authorization: ***
 *
 * ──── Response ────
 * 200 OK
 * content-type: application/json
 *
 * { "syntax-highlighted": "json body" }
 * ```
 */
fun HttpClientConfig<*>.installCurlieLogging() {
    install(Logging) {
        logger = WireLogger
        level = if (wireLog.isDebugEnabled()) LogLevel.ALL else LogLevel.NONE
        sanitizeHeader { it.lowercase() in SENSITIVE_HEADERS }
    }
}

/**
 * Ktor [Logger] that routes formatted HTTP request/response messages through the
 * `de.gematik.zeta.http.wire` SLF4J logger. Reuse this when wiring third-party HTTP clients
 * (e.g. the Zeta SDK's [io.ktor.client.plugins.logging.Logging] plugin) so they pick up the
 * same curlie-style colorisation gated on `-vv`.
 */
internal object WireLogger : Logger {
    override fun log(message: String) {
        wireLog.debug { reformatHttpLog(message) }
    }
}

// Ktor's Logging plugin emits "METHOD: HttpMethod(value=GET)" — strip the wrapper.
private val ktorMethodPattern = Regex("""value=(\w+)""")

internal fun reformatHttpLog(raw: String): String {
    val out = StringBuilder()
    var bodyLines: MutableList<String>? = null
    var contentType: String? = null
    var pendingMethod: String? = null
    var pendingUrl: String? = null
    var inResponse = false

    fun flushRequestLine() {
        if (pendingMethod != null || pendingUrl != null) {
            val m = pendingMethod ?: "?"
            val u = pendingUrl ?: ""
            out.appendLine("${methodStyle(m)} ${urlStyle(u)} ${protoStyle("HTTP/1.1")}")
            pendingMethod = null
            pendingUrl = null
        }
    }

    raw.lineSequence().forEach { line ->
        when {
            line.startsWith("REQUEST: ") -> {
                inResponse = false
                out.appendLine(sectionRule(centeredRule("Request")))
                pendingUrl = line.removePrefix("REQUEST: ")
            }

            line.startsWith("METHOD: ") -> {
                val raw = line.removePrefix("METHOD: ")
                val method = ktorMethodPattern.find(raw)?.groupValues?.get(1) ?: raw
                if (!inResponse) pendingMethod = method
                // Response METHOD echoes the request — ignore.
            }

            line.startsWith("RESPONSE: ") -> {
                inResponse = true
                flushRequestLine()
                out.appendLine(sectionRule(centeredRule("Response")))
                out.appendLine(styleStatus(line.removePrefix("RESPONSE: ")))
            }

            line.startsWith("FROM: ") -> {
                Unit
            }

            // duplicate URL — already shown in request line

            line == "COMMON HEADERS" || line == "CONTENT HEADERS" -> {
                Unit
            }

            // section markers

            line.startsWith("-> ") -> {
                flushRequestLine()
                out.appendLine(formatHeader(line.removePrefix("-> ")))
            }

            line.startsWith("BODY Content-Type: ") -> {
                contentType = line.removePrefix("BODY Content-Type: ").trim().takeIf { it != "null" }
            }

            line == "BODY START" -> {
                bodyLines = mutableListOf()
            }

            line == "BODY END" -> {
                val body = bodyLines?.joinToString("\n")?.trim()
                bodyLines = null
                if (!body.isNullOrEmpty()) {
                    out.appendLine()
                    out.appendLine(formatBody(body, contentType))
                }
                contentType = null
            }

            bodyLines != null -> {
                bodyLines += line
            }
        }
    }

    flushRequestLine()
    val body = out.toString().trimEnd()
    // Closing rule only after the response — request → response shouldn't have a rule between them.
    return if (inResponse) "$body\n${sectionRule(GROUP_END_RULE)}" else body
}

private const val RULE_WIDTH = 60
private val GROUP_END_RULE = "─".repeat(RULE_WIDTH)

private fun centeredRule(title: String): String {
    val pad = (RULE_WIDTH - title.length - 2).coerceAtLeast(2)
    val left = pad / 2
    val right = pad - left
    return "${"─".repeat(left)} $title ${"─".repeat(right)}"
}

private fun formatHeader(line: String): String {
    val idx = line.indexOf(':')
    if (idx < 0) return line
    val name = line.substring(0, idx)
    val value = line.substring(idx + 1).trim()
    return "${headerNameStyle(name)}: $value"
}

private fun styleStatus(status: String): String = pickStatusStyle(status.split(" ").firstOrNull()?.toIntOrNull())(status)

private fun pickStatusStyle(code: Int?): TextStyle =
    when {
        code == null -> boldStyle
        code in 200..299 -> statusOkStyle
        code in 300..399 -> statusRedirStyle
        code in 400..499 -> status4xxStyle
        code in 500..599 -> status5xxStyle
        else -> boldStyle
    }

private fun formatBody(
    body: String,
    contentType: String?,
): String {
    val ct = contentType?.lowercase().orEmpty()
    val trimmed = body.trimStart()
    return when {
        "json" in ct -> tryRenderJson(body)

        "xml" in ct -> renderXml(body, colorize = true)

        // No useful content-type? Sniff: a body that starts with `{`/`[` is almost certainly JSON.
        ct.isEmpty() && (trimmed.startsWith("{") || trimmed.startsWith("[")) -> tryRenderJson(body)

        ct.isEmpty() && trimmed.startsWith("<") -> renderXml(body, colorize = true)

        else -> body
    }
}

private fun tryRenderJson(body: String): String =
    runCatching {
        renderJson(Json.parseToJsonElement(body), colorize = true)
    }.getOrDefault(body)
