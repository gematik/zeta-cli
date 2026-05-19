package de.gematik.zeta.cli.client

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import de.gematik.zeta.cli.output.renderJson
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

private val log = KotlinLogging.logger {}

/**
 * `zeta http URL` — curl-flavoured client for Zeta-protected HTTP resources. The SDK is built
 * against the popp resource ([POPP_RESOURCE]); the URL must be reachable on that resource for
 * the DPoP-bound access token to be accepted.
 *
 * Output convention:
 *   - Without `-i`: only the response body to stdout (pretty-printed if `Content-Type` says
 *     JSON), so `… | jq` works.
 *   - With `-i`: HTTP status line + headers + blank line + body, all to stdout — like curl.
 *   - Logging (Ktor wire, SDK timings, errors) goes through Logback to stderr; raise with `-v`.
 */
class HttpCommand : ZetaSessionCommand("http") {
    private val url: String by argument(
        name = "URL",
        help = "Request URL (must be on the Zeta resource the SDK is built for).",
    )

    private val requestMethod: String by option(
        "-X", "--request",
        metavar = "METHOD",
        envvar = "ZETA_HTTP_METHOD",
        help = "HTTP method to use. Default: GET. (env: ZETA_HTTP_METHOD)",
    ).default("GET")

    private val requestHeaders: List<String> by option(
        "-H", "--header",
        metavar = "NAME: VALUE",
        envvar = "ZETA_HTTP_HEADER",
        help = "Extra request header. Format 'Name: Value' — split on the first ':', both " +
            "sides trimmed. Repeat the flag for multiple headers " +
            "(e.g. -H 'X-A: 1' -H 'X-B: 2'); in zeta.yaml use a YAML list under " +
            "http.header; the env var supplies one. (env: ZETA_HTTP_HEADER)",
    ).multiple()

    private val requestBody: String? by option(
        "-d", "--data",
        metavar = "DATA",
        envvar = "ZETA_HTTP_DATA",
        help = "Request body as a literal string. Forces method to POST when -X is not set. " +
            "(env: ZETA_HTTP_DATA)",
    )

    private val include: Boolean by option(
        "-i", "--include",
        envvar = "ZETA_HTTP_INCLUDE",
        help = "Include the HTTP response status line and headers in the output. " +
            "(env: ZETA_HTTP_INCLUDE)",
    ).flag(default = false)

    private val scopes: List<String> by option(
        "-s", "--scope",
        metavar = "NAME",
        envvar = "ZETA_SCOPE",
        help = "OAuth2 scope to request from the Zeta-Guard auth server. Repeatable; at least " +
            "one is required. The env var supplies a single scope. (env: ZETA_SCOPE)",
    ).multiple(required = true)

    private val poppToken: String? by option(
        "-p", "--popp-token",
        metavar = "TOKEN",
        envvar = "ZETA_POPP_TOKEN",
        help = "Proof of Patient Presence token. Sent as the '$POPP_HEADER_NAME' header per " +
            "gematik ZETA spec (A_25669). (env: ZETA_POPP_TOKEN)",
    )

    override fun help(context: Context) =
        "Send an HTTP request to a Zeta-protected resource."

    override fun runCommand() {
        val poppHeader = poppToken?.let { listOf(POPP_HEADER_NAME to it) }.orEmpty()
        val parsedHeaders = poppHeader + requestHeaders.map(::parseHeaderOption)
        val method = resolveMethod()

        openSession(resource = originOf(url), scopes = scopes) { sdk, _ ->
            val client = sdk.httpClient { applyCliHttpDefaults(cliConfig) }
            try {
                runBlocking {
                    val response = sendRequest(client, method, parsedHeaders, requestBody)
                    printResponse(response)
                }
            } finally {
                client.close()
            }
        }
    }

    /** When `-d` is set without an explicit `-X`, default to POST (curl's behaviour). */
    private fun resolveMethod(): HttpMethod {
        val explicit = requestMethod.uppercase()
        if (explicit != "GET") return HttpMethod.parse(explicit)
        if (requestBody != null) return HttpMethod.Post
        return HttpMethod.Get
    }

    private suspend fun sendRequest(
        client: ZetaHttpClient,
        method: HttpMethod,
        headers: List<Pair<String, String>>,
        body: String?,
    ): ZetaHttpResponse =
        client.request(url) {
            this.method = method
            for ((name, value) in headers) header(name, value)
            body?.let { setBody(it) }
        }

    private suspend fun printResponse(response: ZetaHttpResponse) {
        if (include) {
            // curl-style: "HTTP/1.1 200 OK" + headers + blank line, all to stdout.
            println("HTTP/1.1 ${response.status.value} ${response.status.description}")
            for ((name, value) in response.headers) println("$name: $value")
            println()
        }
        val body = response.bodyAsText()
        if (body.isEmpty()) return

        if (looksLikeJson(response.contentTypeOrEmpty())) {
            val element = runCatching { Json.parseToJsonElement(body) }.getOrNull()
            if (element != null) {
                println(renderJson(element, colorize = colorize))
                return
            }
            log.warn { "response Content-Type claims JSON but body is not parseable; printing raw" }
        }
        println(body)
    }

    private fun ZetaHttpResponse.contentTypeOrEmpty(): String =
        headers.entries.firstOrNull { it.key.equals(HttpHeaders.ContentType, ignoreCase = true) }
            ?.value
            .orEmpty()

    private fun looksLikeJson(contentType: String): Boolean =
        contentType.contains("application/json", ignoreCase = true) ||
            contentType.contains("+json", ignoreCase = true)

}
