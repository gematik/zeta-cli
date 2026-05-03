package de.gematik.zeta.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

/**
 * OAuth Protected Resource Metadata, as defined in RFC 9728.
 *
 * Doubles as the wire format (the `@SerialName` annotations match the spec) and the
 * domain model exposed to consumers. Returned by [ProtectedResourceClient.fetch].
 *
 * [raw] holds the complete, untyped JSON object returned by the server — useful for
 * extension fields not modelled here. It's populated by the client after decoding and
 * excluded from serialization.
 */
@Serializable
data class ProtectedResource(
    val resource: String,
    @SerialName("authorization_servers") val authorizationServers: List<String> = emptyList(),
    @SerialName("jwks_uri") val jwksUri: String? = null,
    @SerialName("scopes_supported") val scopesSupported: List<String> = emptyList(),
    @SerialName("bearer_methods_supported") val bearerMethodsSupported: List<String> = emptyList(),
    @SerialName("resource_signing_alg_values_supported")
    val resourceSigningAlgValuesSupported: List<String> = emptyList(),
    @SerialName("resource_documentation") val resourceDocumentation: String? = null,
    @SerialName("resource_policy_uri") val resourcePolicyUri: String? = null,
    @SerialName("resource_tos_uri") val resourceTosUri: String? = null,
    @Transient
    val raw: JsonObject = JsonObject(emptyMap()),
)
