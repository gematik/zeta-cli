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
package de.gematik.connector

import de.gematik.connector.api.soap.SoapEnvelope
import de.gematik.connector.api.soap.SoapOperation
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.withCharset
import kotlin.text.Charsets
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Wraps a single SOAP endpoint of one Connector service version. Stateless apart from
 * the [endpoint] URL — safe to share or to rebuild ad-hoc from a [ServiceVersion].
 *
 * The proxy *does not* check HTTP status codes: SOAP 1.1 transports faults via HTTP 500
 * with a Fault envelope body. It does check the body: a fault throws [SoapFaultException]
 * from here, where the raw XML is still at hand and the Konnektor's own words can be read
 * out of it — the decoded envelope no longer carries them (see [SoapFault]).
 */
class ServiceProxy internal constructor(
    val httpClient: HttpClient,
    val endpoint: String,
    val service: Service,
    val serviceVersion: ServiceVersion,
) {
    override fun toString(): String =
        "ServiceProxy(${service.name} ${serviceVersion.version} -> $endpoint)"

    suspend fun <Req : SoapEnvelope, Res : SoapEnvelope> call(
        operation: SoapOperation,
        request: Req,
        requestSerializer: KSerializer<Req>,
        responseSerializer: KSerializer<Res>,
        label: String = operation.name,
    ): Res {
        check(endpoint.isNotBlank()) {
            "service ${service.name} version ${serviceVersion.version} has no endpoint"
        }

        val body = defaultXml.encodeToString(requestSerializer, request)

        val response = httpClient.post(endpoint) {
            contentType(ContentType.Text.Xml.withCharset(Charsets.UTF_8))
            header("SOAPAction", operation.soapAction)
            setBody(body)
        }
        val responseText = response.bodyAsText()

        val decoded = try {
            defaultXml.decodeFromString(responseSerializer, responseText)
        } catch (e: Exception) {
            // A fault the generated types cannot express — a service that leaves out a field its own
            // schema calls mandatory, say — is still a fault, and its text is what the caller needs.
            SoapFault.parse(responseText)?.let { fault ->
                throw SoapFaultException(label, fault, envelope = null, rawBody = responseText)
            }
            throw SoapDecodeException(
                "decoding ${operation.name} response from $endpoint: ${e.message}",
                cause = e,
                rawBody = responseText,
            )
        }
        if (decoded.isFault()) {
            throw SoapFaultException(label, SoapFault.parse(responseText), decoded, responseText)
        }
        return decoded
    }
}

/**
 * Reified-generic shorthand for [ServiceProxy.call]: derives both serializers from the
 * Kotlin types so callers don't have to thread `Foo.serializer()` through.
 *
 * ```
 * val resp: GetCardsResponseEnvelope = proxy.call(Operations.GetCards, envelope)
 * ```
 */
suspend inline fun <reified Req : SoapEnvelope, reified Res : SoapEnvelope> ServiceProxy.call(
    operation: SoapOperation,
    request: Req,
    label: String = operation.name,
): Res = call(operation, request, serializer(), serializer(), label)

class SoapDecodeException(
    message: String,
    cause: Throwable? = null,
    val rawBody: String,
) : ConnectorException(message, cause)

class SoapFaultException(
    operation: String,
    /** The fault read off the wire; null only when the body could not be parsed at all. */
    val fault: SoapFault?,
    /** The decoded response, for a caller that wants the typed body — absent when it would not decode. */
    val envelope: SoapEnvelope? = null,
    /** The response body the fault arrived in, for anything [SoapFault] does not model. */
    val rawBody: String? = null,
) : ConnectorException(soapFaultMessage(operation, fault)) {

    /** The SOAP `faultstring` — the Konnektor's human-readable reason. */
    val faultstring: String? = fault?.faultstring

    /** The SOAP `faultcode`, if present. */
    val faultcode: String? = fault?.faultcode
}

private fun soapFaultMessage(operation: String, fault: SoapFault?): String {
    val detail = fault?.describe()
    return if (detail == null) "$operation reported a SOAP fault" else "$operation reported a SOAP fault: $detail"
}
