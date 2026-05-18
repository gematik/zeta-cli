package de.gematik.zeta.cli.trace

import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin

/**
 * Ktor client plugin that opens an `http.request` span around every outbound call. Spans
 * nest naturally under whatever connector / lifecycle span is active. No-op when tracing
 * is disabled (the underlying [Tracer.spanSuspend] short-circuits).
 *
 * Installed only on the CLI's own [io.ktor.client.HttpClient] (built by
 * [de.gematik.zeta.cli.http.createHttpClient]). The SDK constructs its own Ktor clients
 * internally; their requests show up as wire-dump log lines via [de.gematik.zeta.cli.http.SdkLogBridge]
 * but not as spans — closing that gap requires an SDK-side hook.
 */
val HttpTracingPlugin = createClientPlugin("HttpTracing") {
    on(Send) { request ->
        Tracer.spanSuspend(
            "http.request",
            attrs = mapOf(
                "method" to request.method.value,
                "host" to request.url.host,
                "path" to request.url.pathSegments.joinToString("/"),
            ),
        ) { span ->
            val call = proceed(request)
            span.attr("status", call.response.status.value)
            call
        }
    }
}
