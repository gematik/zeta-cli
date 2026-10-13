/*
 * Copyright (Change Date see Readme), gematik GmbH
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the
 * European Commission – subsequent versions of the EUPL (the "Licence").
 * You may not use this work except in compliance with the Licence.
 *
 * You find a copy of the Licence in the "Licence" file or at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.
 * In case of changes by gematik GmbH find details in the "Readme" file.
 *
 * See the Licence for the specific language governing permissions and limitations under the Licence.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 */
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
