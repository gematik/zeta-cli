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
 * Wraps a single SOAP endpoint of one Konnektor service version. Stateless apart from
 * the [endpoint] URL — safe to share or to rebuild ad-hoc from a [ServiceVersion].
 *
 * The proxy *does not* check HTTP status codes: SOAP 1.1 transports faults via HTTP 500
 * with a Fault envelope body. Callers should inspect the response via [SoapEnvelope.isFault]
 * (or use a convenience method on [KonnektorClient] that does this for them).
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

        return try {
            defaultXml.decodeFromString(responseSerializer, responseText)
        } catch (e: Exception) {
            throw SoapDecodeException(
                "decoding ${operation.name} response from $endpoint: ${e.message}",
                cause = e,
                rawBody = responseText,
            )
        }
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
): Res = call(operation, request, serializer(), serializer())

/**
 * Throw if [this] is a SOAP fault envelope, otherwise return it untouched. Convenience
 * for the typical "extract response or surface fault" pattern at the convenience-method
 * layer.
 *
 * The fault detail (faultstring, faultcode, error detail) lives on the typed Fault
 * field of the envelope; cast and read it from the [SoapFaultException.envelope] when
 * caught.
 */
fun <T : SoapEnvelope> T.requireSuccess(operation: String): T {
    if (isFault()) throw SoapFaultException(operation, envelope = this)
    return this
}

class SoapDecodeException(
    message: String,
    cause: Throwable? = null,
    val rawBody: String,
) : ConnectorException(message, cause)

class SoapFaultException(
    operation: String,
    val envelope: SoapEnvelope,
) : ConnectorException("$operation reported a SOAP fault")
