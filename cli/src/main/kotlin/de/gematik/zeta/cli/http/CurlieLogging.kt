package de.gematik.zeta.cli.http

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles
import de.gematik.zeta.cli.output.renderJson
import de.gematik.zeta.cli.output.renderXml
import de.gematik.zeta.cli.term.StderrColors
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

// Pass strings through unchanged when stderr is redirected — keeps `zeta -vv 2> file` plain.
private fun TextStyle.maybe(s: String): String = if (StderrColors.enabled) this(s) else s

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
object WireLogger : Logger {
    override fun log(message: String) {
        wireLog.debug { reformatHttpLog(message) }
    }
}

/**
 * The [LogLevel] [WireLogger] should be installed at — `ALL` when the user passed `-vv` (or
 * higher) so wire dumps actually fire, `NONE` otherwise so Ktor doesn't waste time building
 * the request/response message strings just for them to be dropped.
 */
val wireLogLevel: LogLevel
    get() = if (wireLog.isDebugEnabled()) LogLevel.ALL else LogLevel.NONE

// Ktor's Logging plugin emits "METHOD: HttpMethod(value=GET)" — strip the wrapper.
private val ktorMethodPattern = Regex("""value=(\w+)""")

internal fun reformatHttpLog(raw: String): String {
    val out = StringBuilder()
    var bodyLines: MutableList<String>? = null
    var contentType: String? = null
    var pendingMethod: String? = null
    var pendingUrl: String? = null
    var inResponse = false
    val jwts = mutableListOf<JwtFinding>()

    fun flushRequestLine() {
        if (pendingMethod != null || pendingUrl != null) {
            val m = pendingMethod ?: "?"
            val u = pendingUrl ?: ""
            out.appendLine("${methodStyle.maybe(m)} ${urlStyle.maybe(u)} ${protoStyle.maybe("HTTP/1.1")}")
            pendingMethod = null
            pendingUrl = null
        }
    }

    raw.lineSequence().forEach { line ->
        when {
            line.startsWith("REQUEST: ") -> {
                inResponse = false
                out.appendLine(sectionRule.maybe(centeredRule("Request")))
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
                out.appendLine(sectionRule.maybe(centeredRule("Response")))
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
                val rest = line.removePrefix("-> ")
                out.appendLine(formatHeader(rest))
                collectJwtsFromHeader(rest, jwts)
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
                    jwts += findJwtsInBody(body, contentType)
                }
                contentType = null
            }

            bodyLines != null -> {
                bodyLines += line
            }
        }
    }

    flushRequestLine()
    if (jwts.isNotEmpty()) {
        out.appendLine()
        out.appendLine(formatJwtSection(jwts))
    }
    val body = out.toString().trimEnd()
    // Closing rule only after the response — request → response shouldn't have a rule between them.
    return if (inResponse) "$body\n${sectionRule.maybe(GROUP_END_RULE)}" else body
}

private const val RULE_WIDTH = 60

// Box-drawing horizontal on UTF-8-capable consoles; ASCII fallback for legacy Windows
// code pages (CP-850/CP-1252) where U+2500's UTF-8 bytes show up as mojibake (`ÔöÇ`).
private val RULE_CHAR = if (StderrColors.unicode) "─" else "-"
private val GROUP_END_RULE = RULE_CHAR.repeat(RULE_WIDTH)

private fun centeredRule(title: String): String {
    val pad = (RULE_WIDTH - title.length - 2).coerceAtLeast(2)
    val left = pad / 2
    val right = pad - left
    return "${RULE_CHAR.repeat(left)} $title ${RULE_CHAR.repeat(right)}"
}

private fun formatHeader(line: String): String {
    val idx = line.indexOf(':')
    if (idx < 0) return line
    val name = line.substring(0, idx)
    val value = line.substring(idx + 1).trim()
    return "${headerNameStyle.maybe(name)}: $value"
}

private fun styleStatus(status: String): String =
    pickStatusStyle(status.split(" ").firstOrNull()?.toIntOrNull()).maybe(status)

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
    val color = StderrColors.enabled
    return when {
        "json" in ct -> tryRenderJson(body, color)

        "xml" in ct -> renderXml(body, colorize = color)

        // No useful content-type? Sniff: a body that starts with `{`/`[` is almost certainly JSON.
        ct.isEmpty() && (trimmed.startsWith("{") || trimmed.startsWith("[")) -> tryRenderJson(body, color)

        ct.isEmpty() && trimmed.startsWith("<") -> renderXml(body, colorize = color)

        // Binary content-types: Ktor's Logging plugin gives us UTF-8-decoded bytes, which
        // is gibberish for non-text payloads. Suppress like curlie does.
        ct.isNotEmpty() && !isTextLike(ct) -> protoStyle.maybe("<binary $contentType body — not shown>")

        else -> body
    }
}

/**
 * Heuristic for "render this as text in the wire log". Whitelist of text-ish media-type
 * markers — anything else with a non-empty content-type is treated as binary and replaced
 * with a placeholder. Mirrors curlie/httpie's body-suppression behaviour.
 */
private fun isTextLike(ct: String): Boolean =
    ct.startsWith("text/") ||
        "json" in ct ||
        "xml" in ct ||
        "javascript" in ct ||
        "yaml" in ct ||
        "x-www-form-urlencoded" in ct ||
        "csv" in ct

private fun tryRenderJson(body: String, colorize: Boolean): String =
    runCatching {
        renderJson(Json.parseToJsonElement(body), colorize = colorize)
    }.getOrDefault(body)

// ──────────────────────────────────────────────────────────────────────────
//  Decoded-JWT section
// ──────────────────────────────────────────────────────────────────────────

/**
 * Three base64url chunks separated by dots. The 16-char minimum keeps the regex from
 * matching things like file paths that happen to contain the right alphabet — the JOSE
 * header alone is rarely shorter once `alg` and `typ` are present.
 */
private val jwtRegex = Regex("""[A-Za-z0-9_-]{16,}\.[A-Za-z0-9_-]{16,}\.[A-Za-z0-9_-]{16,}""")

// Per-process JWT registry: each distinct full-JWT string gets a stable numeric handle
// (`[jwt#N]`) the first time it's seen, and the same handle on every repeat thereafter.
// Lets `formatJwtSection` decode each JWT once and reference it cheaply afterwards
// — a long trace with the same access token on every request stops re-dumping the
// same 30-line claims block over and over.
private val jwtRegistry: ConcurrentHashMap<String, Int> = ConcurrentHashMap()
private val nextJwtId = AtomicInteger(0)

private data class JwtFinding(
    val source: String,
    val joseHeader: JsonElement,
    val payload: JsonElement,
    val id: Int,
    val firstSeen: Boolean,
)

/** Assign or look up the handle for [raw]. `firstSeen` is true on the first sighting only. */
private fun registerJwt(raw: String): Pair<Int, Boolean> {
    var firstSeen = false
    val id = jwtRegistry.computeIfAbsent(raw) {
        firstSeen = true
        nextJwtId.incrementAndGet()
    }
    return id to firstSeen
}

private fun collectJwtsFromHeader(headerLine: String, into: MutableList<JwtFinding>) {
    val sep = headerLine.indexOf(':')
    if (sep < 0) return
    val name = headerLine.substring(0, sep).trim()
    val value = headerLine.substring(sep + 1).trim()
    into += findJwts(value, "header[$name]")
}

private fun findJwts(text: String, source: String): List<JwtFinding> =
    jwtRegex.findAll(text).mapNotNull { decodeJwt(it.value, source) }.toList()

/** Decode a candidate match. `null` if either of the first two parts isn't base64url-JSON, or if
 *  the JOSE header doesn't look JWT-shaped (no `alg` or `typ`) — keeps random three-dot strings out. */
private fun decodeJwt(raw: String, source: String): JwtFinding? {
    val parts = raw.split('.')
    if (parts.size != 3) return null
    val joseHeader = decodeBase64UrlJson(parts[0]) as? JsonObject ?: return null
    if ("alg" !in joseHeader && "typ" !in joseHeader) return null
    val payload = decodeBase64UrlJson(parts[1]) ?: return null
    val (id, firstSeen) = registerJwt(raw)
    return JwtFinding(source, joseHeader, payload, id, firstSeen)
}

private fun decodeBase64UrlJson(s: String): JsonElement? = runCatching {
    val pad = "=".repeat((4 - s.length % 4) % 4)
    val bytes = Base64.getUrlDecoder().decode(s + pad)
    Json.parseToJsonElement(bytes.decodeToString())
}.getOrNull()

/** Walk a body for JWTs in a content-type-aware way. */
private fun findJwtsInBody(body: String, contentType: String?): List<JwtFinding> {
    val ct = contentType?.lowercase().orEmpty()
    val trimmed = body.trimStart()
    return when {
        "x-www-form-urlencoded" in ct -> findJwtsInForm(body)

        "json" in ct || trimmed.startsWith("{") || trimmed.startsWith("[") -> {
            val element = runCatching { Json.parseToJsonElement(body) }.getOrNull()
            element?.let { walkJsonStrings(it, "").flatMap { (path, str) -> findJwts(str, "body.$path") }.toList() }
                ?: findJwts(body, "body")
        }

        else -> findJwts(body, "body")
    }
}

private fun findJwtsInForm(body: String): List<JwtFinding> =
    body.split('&').flatMap { pair ->
        val idx = pair.indexOf('=')
        if (idx < 0) return@flatMap emptyList()
        val key = URLDecoder.decode(pair.substring(0, idx), Charsets.UTF_8)
        val value = URLDecoder.decode(pair.substring(idx + 1), Charsets.UTF_8)
        findJwts(value, "body.$key")
    }

/** Yield every string-leaf with its dotted JSON path, e.g. `claims.access_token`, `tokens[0]`. */
private fun walkJsonStrings(element: JsonElement, path: String): Sequence<Pair<String, String>> = sequence {
    when (element) {
        is JsonObject -> element.entries.forEach { (k, v) ->
            yieldAll(walkJsonStrings(v, if (path.isEmpty()) k else "$path.$k"))
        }
        is JsonArray -> element.forEachIndexed { i, v ->
            yieldAll(walkJsonStrings(v, "$path[$i]"))
        }
        is JsonPrimitive -> if (element.isString) yield(path.ifEmpty { "$" } to element.content)
    }
}

private fun formatJwtSection(findings: List<JwtFinding>): String {
    val color = StderrColors.enabled
    return buildString {
        append(sectionRule.maybe(centeredRule("Decoded JWTs")))
        findings.forEach { jwt ->
            appendLine()
            // Source + numeric handle. Handle is muted so the source name still reads first.
            appendLine("${headerNameStyle.maybe(jwt.source)}  ${protoStyle.maybe("[jwt#${jwt.id}]")}")
            if (jwt.firstSeen) {
                appendLine("  ${boldStyle.maybe("header")}")
                appendLine(indent(renderJson(jwt.joseHeader, colorize = color), "    "))
                appendLine("  ${boldStyle.maybe("payload")}")
                append(indent(renderJson(jwt.payload, colorize = color), "    "))
            } else {
                append("  ${protoStyle.maybe("(decoded earlier)")}")
            }
        }
    }
}

private fun indent(s: String, prefix: String): String =
    s.lineSequence().joinToString("\n") { "$prefix$it" }
