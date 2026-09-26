package de.gematik.zeta.cli.probe

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetSocketAddress

/**
 * Liveness and readiness for an orchestrator, on the JDK's built-in HTTP server. `GET /healthz` answers
 * `200 ok` while [livenessProblem] returns null and `503` with the problem otherwise; `GET /readyz`
 * answers `200 ready` once [notReadyReason] returns null, else `503` with that reason. Both are about the
 * prober itself, not the collector: a collector outage only delays exports.
 */
internal class ProbeHealthServer(
    host: String,
    port: Int,
    private val notReadyReason: () -> String?,
    private val livenessProblem: () -> String? = { null },
) : Closeable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0).apply {
        createContext("/healthz") { exchange -> check(exchange, "ok", "liveness", livenessProblem) }
        createContext("/readyz") { exchange -> check(exchange, "ready", "readiness", notReadyReason) }
    }

    private fun check(exchange: HttpExchange, okBody: String, what: String, problem: () -> String?) {
        val reason = runCatching { problem() }.getOrElse { "$what check failed: ${it.message}" }
        if (reason == null) respond(exchange, 200, okBody) else respond(exchange, 503, reason)
    }

    val port: Int get() = server.address.port

    fun start(): ProbeHealthServer = apply { server.start() }

    override fun close() = server.stop(0)

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        exchange.use {
            if (it.requestMethod != "GET" && it.requestMethod != "HEAD") {
                it.sendResponseHeaders(405, -1)
                return
            }
            val bytes = "$body\n".toByteArray()
            it.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            if (it.requestMethod == "HEAD") {
                it.sendResponseHeaders(status, -1)
            } else {
                it.sendResponseHeaders(status, bytes.size.toLong())
                it.responseBody.use { out -> out.write(bytes) }
            }
        }
    }
}
