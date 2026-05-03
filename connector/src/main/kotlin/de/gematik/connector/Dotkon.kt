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
 * Pure data: certs stay as their base64 strings here so the type is free of JVM-only
 * crypto types. The TLS / PKCS#12 wiring lives in
 * [de.gematik.connector.engine.okhttp.dotkonOkHttpClient].
 */
@Serializable
data class Dotkon(
    val version: String? = null,
    val url: String,
    val rewriteServiceEndpoints: Boolean = false,
    val mandantId: String,
    val workplaceId: String,
    val clientSystemId: String,
    val userId: String? = null,
    val credentials: Credentials,
    val env: String? = null,
    val insecureSkipVerify: Boolean = false,
    val expectedHost: String? = null,
    val trustStore: List<String> = emptyList(),
)

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

internal fun expandEnvVars(text: String, envLookup: (String) -> String?): String =
    ENV_VAR_PATTERN.replace(text) { match -> envLookup(match.groupValues[1]) ?: "" }

private val VALID_ENV_VALUES = setOf("ru", "tu", "pu")

private fun Dotkon.validationErrors(): List<String> = buildList {
    if (url.isBlank()) add(""""url" is required""")
    if (mandantId.isBlank()) add(""""mandantId" is required""")
    if (workplaceId.isBlank()) add(""""workplaceId" is required""")
    if (clientSystemId.isBlank()) add(""""clientSystemId" is required""")
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
    }
}

private fun parseFailureMessage(e: SerializationException): String {
    val msg = e.message ?: "unknown parse error"
    // Map kotlinx-serialization's discriminator / missing-field / unknown-subclass errors to
    // user-facing wording that names the supported credential types. Anything we don't
    // recognise passes through verbatim.
    return when {
        "credentials" in msg && ("'type'" in msg || "class discriminator" in msg) ->
            "credentials.type is required (must be basic or pkcs12)"
        // kotlinx 2.x: "Serializer for subclass 'X' is not found in the polymorphic scope of 'Credentials'"
        Regex("""Serializer for subclass '[^']*' is not found.*Credentials""").containsMatchIn(msg) ->
            "credentials.type is not supported (must be basic or pkcs12)"
        msg.contains("Polymorphic serializer was not found", ignoreCase = true) ->
            "credentials.type is not supported (must be basic or pkcs12)"
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
