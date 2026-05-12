package de.gematik.zeta.cli.inspect

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** OAuth Protected Resource Metadata, RFC 9728. */
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
)
