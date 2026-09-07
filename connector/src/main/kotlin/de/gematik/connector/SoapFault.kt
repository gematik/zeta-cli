package de.gematik.connector

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import nl.adaptivity.xmlutil.serialization.XmlValue

private const val SOAP_ENV_NS = "http://schemas.xmlsoap.org/soap/envelope/"
private const val TEL_ERROR_NS = "http://ws.gematik.de/tel/error/v2.0"

/**
 * A SOAP 1.1 fault, read straight off the wire rather than out of a typed response envelope.
 *
 * The generated `*ResponseEnvelope.Fault` types declare `faultcode` / `faultstring` / `detail`
 * without a namespace of their own, which xmlutil resolves against the enclosing `SOAP-ENV`
 * namespace — but SOAP 1.1 sends those three **unqualified**. With `ignoreUnknownChildren()` the
 * mismatch is silent: the fault decodes into a `Fault` whose every field is still at its default,
 * and the only thing left to report is "reported a SOAP fault". Hand-modelling them here, with
 * `namespace = ""` where the spec puts them, is what makes the Konnektor's own words readable.
 * The generated types cannot be fixed in place — they are regenerated from the WSDL.
 */
@Serializable
@XmlSerialName("Envelope", namespace = SOAP_ENV_NS, prefix = "SOAP-ENV")
internal data class SoapFaultEnvelope(
    @XmlElement(true)
    @XmlSerialName("Body", namespace = SOAP_ENV_NS, prefix = "SOAP-ENV")
    val body: Body = Body(),
) {
    @Serializable
    data class Body(
        @XmlElement(true)
        @XmlSerialName("Fault", namespace = SOAP_ENV_NS, prefix = "SOAP-ENV")
        val fault: Fault? = null,
    )

    @Serializable
    data class Fault(
        @XmlElement(true)
        @XmlSerialName("faultcode", namespace = "", prefix = "")
        val faultcode: String? = null,
        @XmlElement(true)
        @XmlSerialName("faultstring", namespace = "", prefix = "")
        val faultstring: String? = null,
        @XmlElement(true)
        @XmlSerialName("faultactor", namespace = "", prefix = "")
        val faultactor: String? = null,
        @XmlElement(true)
        @XmlSerialName("detail", namespace = "", prefix = "")
        val detail: Detail? = null,
    )

    /** The gematik `Error` document Konnektors put in `<detail>`; every field optional by design. */
    @Serializable
    data class Detail(
        @XmlElement(true)
        @XmlSerialName("Error", namespace = TEL_ERROR_NS, prefix = "err")
        val error: Error? = null,
    )

    @Serializable
    data class Error(
        @XmlElement(true)
        @XmlSerialName("Trace", namespace = TEL_ERROR_NS, prefix = "err")
        val trace: List<Trace> = emptyList(),
    )

    @Serializable
    data class Trace(
        @XmlElement(true)
        @XmlSerialName("Code", namespace = TEL_ERROR_NS, prefix = "err")
        val code: String? = null,
        @XmlElement(true)
        @XmlSerialName("ErrorText", namespace = TEL_ERROR_NS, prefix = "err")
        val errorText: String? = null,
        @XmlElement(true)
        @XmlSerialName("ErrorType", namespace = TEL_ERROR_NS, prefix = "err")
        val errorType: String? = null,
        @XmlElement(true)
        @XmlSerialName("Severity", namespace = TEL_ERROR_NS, prefix = "err")
        val severity: String? = null,
        @XmlElement(true)
        @XmlSerialName("Detail", namespace = TEL_ERROR_NS, prefix = "err")
        val detail: TraceDetail? = null,
    )

    @Serializable
    data class TraceDetail(
        @XmlValue(true)
        val charData: String = "",
    )
}

/** What a Konnektor said went wrong, flattened into the parts worth printing. */
data class SoapFault(
    val faultcode: String?,
    val faultstring: String?,
    /** gematik error code from `<detail><Error><Trace><Code>`, e.g. `4018`. */
    val errorCode: String?,
    /** The `ErrorText`, plus the free-text `Detail` when the Konnektor sent one. */
    val errorText: String?,
) {
    /** One line: what the service said, in the order a reader wants it. */
    fun describe(): String? {
        val head = faultstring?.trim()?.takeIf { it.isNotBlank() }
        val tail = listOfNotNull(
            errorCode?.trim()?.takeIf { it.isNotBlank() }?.let { "code $it" },
            errorText?.trim()?.takeIf { it.isNotBlank() && it != head },
        ).joinToString(": ").takeIf { it.isNotBlank() }
        return listOfNotNull(head, tail?.let { if (head == null) it else "($it)" })
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
    }

    companion object {
        /**
         * The fault in [rawBody], or null when it holds none we can read. Never throws: a fault we
         * cannot parse still has to surface as a fault, just a less descriptive one.
         */
        fun parse(rawBody: String): SoapFault? = runCatching {
            val fault = defaultXml.decodeFromString(SoapFaultEnvelope.serializer(), rawBody).body.fault
                ?: return null
            val trace = fault.detail?.error?.trace?.lastOrNull()
            SoapFault(
                faultcode = fault.faultcode,
                faultstring = fault.faultstring,
                errorCode = trace?.code,
                errorText = listOfNotNull(trace?.errorText, trace?.detail?.charData)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" — ")
                    .takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }
}
