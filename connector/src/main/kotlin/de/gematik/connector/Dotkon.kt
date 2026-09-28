package de.gematik.connector

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Parsed `.kon` configuration file.
 *
 * The format mirrors koap-go's `Dotkon`:
 *
 * ```json
 * {
 *   "version": "1.0.0",
 *   "url": "https://konnektor.example.com:8443",
 *   "mandantId": "M1", "workplaceId": "W1", "clientSystemId": "C1", "userId": "U1",
 *   "credentials": { "type": "basic", "username": "u", "password": "p" },
 *   "env": "ru",
 *   "insecureSkipVerify": false,
 *   "expectedHost": "konnektor.example.com",
 *   "trustStore": ["<base64-DER-cert>", ...]
 * }
 * ```
 *
 * `${VAR}` placeholders anywhere in the raw text are substituted from the environment
 * before parsing, so secrets can stay outside the file.
 *
 * With `"product": "consumer"` the file describes a Basis-Consumer instead: it has no service
 * directory and no call context, so `serviceEndpoints` names each service's path under `url`
 * and the mandant / workplace / client-system ids are not needed.
 *
 * Pure data: certs stay as their base64 strings here so the type is free of JVM-only
 * crypto types. The TLS / PKCS#12 wiring lives in
 * [de.gematik.connector.engine.okhttp.dotkonOkHttpClient].
 */
@Serializable
data class Dotkon(
    val version: String? = null,
    val url: String,
    val rewriteServiceEndpoints: Boolean = false,
    val mandantId: String = "",
    val workplaceId: String = "",
    val clientSystemId: String = "",
    val userId: String? = null,
    val credentials: Credentials,
    val env: String? = null,
    val insecureSkipVerify: Boolean = false,
    val expectedHost: String? = null,
    val trustStore: List<String> = emptyList(),
    val product: Product = Product.Konnektor,
    val serviceEndpoints: List<ConsumerEndpoint> = emptyList(),
)

@Serializable
enum class Product {
    @SerialName("konnektor")
    Konnektor,

    @SerialName("consumer")
    Consumer,
}

/** Where one Basis-Consumer service lives: [path] is resolved against [Dotkon.url]. */
@Serializable
data class ConsumerEndpoint(
    val name: String,
    val path: String,
)

/**
 * Full URL of the Basis-Consumer service [name], from [Dotkon.url] and its configured path.
 *
 * @throws DotkonValidationException when no `serviceEndpoints` entry is named [name].
 */
fun Dotkon.consumerEndpoint(name: String): String {
    val entry = serviceEndpoints.firstOrNull { it.name == name }
        ?: throw DotkonValidationException(listOf("serviceEndpoints has no entry named \"$name\""))
    return url.trimEnd('/') + entry.path
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface Credentials {
    @Serializable
    @SerialName("basic")
    data class Basic(
        val username: String,
        val password: String,
    ) : Credentials

    /**
     * PKCS#12 client certificate.
     *
     * [data] is the base64-encoded PFX container; [password] decrypts it ("" for none).
     */
    @Serializable
    @SerialName("pkcs12")
    data class Pkcs12(
        val data: String,
        val password: String = "",
    ) : Credentials

    /** No client authentication: neither a basic-auth header nor a client certificate is sent. */
    @Serializable
    @SerialName("none")
    data object None : Credentials
}

/** Decoded DER bytes of every cert in [Dotkon.trustStore], in original order. */
@OptIn(ExperimentalEncodingApi::class)
fun Dotkon.trustedCertificateBytes(): List<ByteArray> =
    trustStore.mapIndexed { i, b64 ->
        try {
            // MIME mode tolerates line-wrapped base64 (the typical `base64` / `openssl base64`
            // 64- or 76-char output) while still parsing strict input.
            Base64.Mime.decode(b64)
        } catch (e: IllegalArgumentException) {
            throw DotkonValidationException(
                listOf("trustStore[$i] is not valid base64: ${e.message}"),
            )
        }
    }

/** Decoded PKCS#12 bytes of [Credentials.Pkcs12.data]. */
@OptIn(ExperimentalEncodingApi::class)
fun Credentials.Pkcs12.decodedData(): ByteArray =
    try {
        Base64.Mime.decode(data)
    } catch (e: IllegalArgumentException) {
        throw DotkonValidationException(
            listOf("credentials.data is not valid base64: ${e.message}"),
        )
    }

/**
 * Parse a `.kon` JSON document.
 *
 * `${VAR}` tokens in the raw text are replaced with the value returned by [envLookup]
 * (process environment by default). Missing variables expand to "", matching the Go
 * implementation.
 *
 * @throws DotkonValidationException on missing or malformed fields.
 */
fun parseDotkon(
    json: String,
    envLookup: (String) -> String? = { System.getenv(it) },
    jsonFormat: Json = defaultDotkonJson,
): Dotkon {
    val expanded = expandEnvVars(json, envLookup)
    val dk = try {
        jsonFormat.decodeFromString(Dotkon.serializer(), expanded)
    } catch (e: SerializationException) {
        throw DotkonValidationException(listOf(parseFailureMessage(e)), cause = e)
    }
    val errors = dk.validationErrors()
    if (errors.isNotEmpty()) throw DotkonValidationException(errors)
    return dk
}

private val defaultDotkonJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type" // matches @JsonClassDiscriminator on Credentials
}

private val ENV_VAR_PATTERN = Regex("""\$\{([^}]+)\}""")

/**
 * Expand `${VAR}` placeholders in [text] using [envLookup] (process environment by
 * default). Missing variables expand to the empty string — matches the Go reference
 * implementation and Docker Compose-style substitution behaviour.
 *
 * Public so other CLI surfaces (notably the YAML config-file value source) can apply
 * the same substitution before parsing without re-implementing the regex.
 */
fun expandEnvVars(text: String, envLookup: (String) -> String? = { System.getenv(it) }): String =
    ENV_VAR_PATTERN.replace(text) { match -> envLookup(match.groupValues[1]) ?: "" }

private val VALID_ENV_VALUES = setOf("ru", "tu", "pu")

/** Services the consumer client calls; a consumer `.kon` without either cannot sign anything. */
private val REQUIRED_CONSUMER_SERVICES = listOf("CertificateService", "SignatureService")

private fun Dotkon.validationErrors(): List<String> = buildList {
    if (url.isBlank()) add(""""url" is required""")
    when (product) {
        Product.Konnektor -> {
            if (mandantId.isBlank()) add(""""mandantId" is required""")
            if (workplaceId.isBlank()) add(""""workplaceId" is required""")
            if (clientSystemId.isBlank()) add(""""clientSystemId" is required""")
            if (serviceEndpoints.isNotEmpty()) {
                add(""""serviceEndpoints" is only valid with "product": "consumer"""")
            }
        }
        Product.Consumer -> addAll(consumerEndpointErrors())
    }
    if (env != null && env !in VALID_ENV_VALUES) {
        add(""""env" must be one of ru, tu, pu (got "$env")""")
    }
    when (val c = credentials) {
        is Credentials.Basic -> {
            if (c.username.isBlank()) add("credentials.username is required for basic credentials")
            if (c.password.isBlank()) add("credentials.password is required for basic credentials")
        }
        is Credentials.Pkcs12 -> {
            if (c.data.isBlank()) add("credentials.data is required for pkcs12 credentials")
        }
        Credentials.None -> {}
    }
}

private fun Dotkon.consumerEndpointErrors(): List<String> = buildList {
    // A consumer has no service directory, so there is nothing for the rewrite to act on.
    if (rewriteServiceEndpoints) add(""""rewriteServiceEndpoints" is not valid with "product": "consumer"""")
    serviceEndpoints.forEachIndexed { i, e ->
        if (e.name.isBlank()) add("serviceEndpoints[$i].name is required")
        // Paths only: scheme, host and port come from "url", so one file cannot point its services
        // at different hosts than the one its TLS settings were written for.
        val p = e.path
        if (!p.startsWith("/") || p.startsWith("//") || "://" in p || '?' in p || '#' in p) {
            add("serviceEndpoints[$i].path must be a path starting with \"/\" (got \"$p\")")
        }
    }
    serviceEndpoints.groupBy { it.name }
        .filter { (name, entries) -> name.isNotBlank() && entries.size > 1 }
        .keys.forEach { add("serviceEndpoints has more than one entry named \"$it\"") }
    REQUIRED_CONSUMER_SERVICES
        .filter { name -> serviceEndpoints.none { it.name == name } }
        .forEach { add("serviceEndpoints needs an entry named \"$it\" for \"product\": \"consumer\"") }
}

private fun parseFailureMessage(e: SerializationException): String {
    val msg = e.message ?: "unknown parse error"
    // Map kotlinx-serialization's discriminator / missing-field / unknown-subclass errors to
    // user-facing wording that names the supported credential types. Anything we don't
    // recognise passes through verbatim.
    return when {
        "credentials" in msg && ("'type'" in msg || "class discriminator" in msg) ->
            "credentials.type is required (must be basic, pkcs12 or none)"
        // kotlinx 2.x: "Serializer for subclass 'X' is not found in the polymorphic scope of 'Credentials'"
        Regex("""Serializer for subclass '[^']*' is not found.*Credentials""").containsMatchIn(msg) ->
            "credentials.type is not supported (must be basic, pkcs12 or none)"
        msg.contains("Polymorphic serializer was not found", ignoreCase = true) ->
            "credentials.type is not supported (must be basic, pkcs12 or none)"
        // "de.gematik.connector.Product does not contain element with name 'x' at path $.product"
        "Product does not contain element with name" in msg ->
            """"product" is not supported (must be konnektor or consumer)"""
        else -> msg
    }
}

class DotkonValidationException(
    val errors: List<String>,
    cause: Throwable? = null,
) : ConnectorException(formatMessage(errors), cause) {
    private companion object {
        fun formatMessage(errors: List<String>): String =
            "invalid .kon configuration:\n  - " + errors.joinToString("\n  - ")
    }
}
