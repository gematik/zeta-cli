package de.gematik.zeta.cli.client

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import de.gematik.zeta.cli.output.renderJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

private val log = KotlinLogging.logger {}

/**
 * `zeta ws URL` — websocat-flavoured client for Zeta-protected WebSockets. Reads JSON messages
 * line-by-line from stdin and forwards them; prints server frames as they arrive (full-duplex).
 *
 * Behaviour:
 *   - Each line on stdin is parsed as a JSON value. Unparseable lines are logged at WARN and
 *     skipped — the WebSocket stays open. Pretty-printed JSON spanning multiple lines isn't
 *     supported; pipe through `jq -c` to compact first.
 *   - Server frames print as soon as they arrive (text → pretty-printed JSON; binary →
 *     skipped with a WARN). Output may interleave with the user's typing.
 *   - Exits when the server sends a Close frame (or the connection drops), even if stdin
 *     hasn't been fully consumed. On stdin EOF, the WS is closed and the program exits when
 *     the server's Close reply lands.
 */
class WsCommand : ZetaSessionCommand("ws") {
    private val url: String by argument(
        name = "URL",
        help = "WebSocket URL (must be on the Zeta resource the SDK is built for).",
    )

    private val requestHeaders: List<String> by option(
        "-H", "--header",
        metavar = "HEADER",
        envvar = "ZETA_WS_HEADER",
        help = "Extra header on the WS upgrade request, 'Name: Value'. Repeat the flag for " +
            "multiple headers; the env var supplies one. (env: ZETA_WS_HEADER)",
    ).multiple()

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
        "Open a WebSocket to a Zeta-protected resource and round-trip JSON messages from stdin."

    override fun runCommand() {
        // Build with popp first so an explicit `-H PoPP: …` later wins (last-write).
        val customHeaders = buildMap {
            poppToken?.let { put(POPP_HEADER_NAME, it) }
            putAll(requestHeaders.associate(::parseHeaderOption))
        }

        openSession(resource = originOf(url), scopes = scopes) { sdk, _ ->
            runBlocking {
                sdk.ws(
                    targetUrl = url,
                    builder = { applyCliHttpDefaults(cliConfig) },
                    customHeaders = customHeaders,
                ) {
                    log.info { "WebSocket connected to $url" }
                    relayStdinJson()
                }
            }
        }
    }

    /**
     * Run the sender (stdin → frames) and receiver (frames → stdout) concurrently, full-duplex.
     *
     * The receiver lives in a child coroutine. When the server closes — Close frame or any
     * other end of the incoming channel — its `finally` calls [exitProcess]. That's the only
     * reliable escape from the blocked stdin read: `BufferedReader.readLine()` on `System.in`
     * is wrapped in a JNI call that doesn't honour `Thread.interrupt()` and therefore can't be
     * unwound by Kotlin coroutine cancellation. Cancelling the scope would mark the sender as
     * cancelled, but the underlying JVM thread would sit there until the user happened to
     * type something, which is exactly the bug we're fixing.
     */
    private suspend fun DefaultClientWebSocketSession.relayStdinJson() = coroutineScope {
        val session = this@relayStdinJson

        launch {
            try {
                for (frame in session.incoming) {
                    when (frame) {
                        is Frame.Text -> printJson(frame.readText())
                        is Frame.Binary -> log.warn { "ignoring ${frame.readBytes().size}-byte binary frame from server" }
                        is Frame.Close -> {
                            log.info { "WS closed by server" }
                            break
                        }
                        else -> { /* ping/pong */ }
                    }
                }
            } catch (e: CancellationException) {
                // Ktor's OkHttp engine cancels the incoming channel with a
                // CancellationException when the server sends a normal Close — that's an
                // ordinary close, not a failure. The cancel-message carries the close code.
                log.info { "WS closed: ${e.message ?: "cancelled by engine"}" }
            } catch (e: Throwable) {
                log.error(e) { "WebSocket receive failed" }
            } finally {
                // The blocked stdin read can't be cancelled cooperatively (see method KDoc),
                // so we hard-exit. exitProcess() is idempotent if the sender already broke out.
                exitProcess(0)
            }
        }

        // Sender stays in the scope's main coroutine; on stdin EOF we close the WS, the server
        // replies with Close, and the receiver's `finally` exits.
        val reader = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
            if (line.isBlank()) continue

            try {
                Json.parseToJsonElement(line)
            } catch (e: SerializationException) {
                log.warn { "skipping non-JSON input: ${e.message}" }
                continue
            }
            log.debug { "WS send: $line" }
            try {
                session.send(Frame.Text(line))
            } catch (e: Throwable) {
                // The WS is already closed (typically because the server hung up between
                // our prompt-and-type window). The receiver's finally has scheduled (or is
                // about to schedule) exitProcess; swallow the throw so it doesn't unwind to
                // main as an unhandled exception while the JVM is mid-shutdown.
                log.debug { "send aborted (WS already closed): ${e.message}" }
                break
            }
        }
        runCatching { session.close() }
    }

    private fun printJson(payload: String) {
        val element = runCatching { Json.parseToJsonElement(payload) }.getOrNull()
        if (element == null) {
            log.warn { "server reply is not valid JSON; printing raw" }
            println(payload)
            return
        }
        println(renderJson(element, colorize = colorize))
    }

}
