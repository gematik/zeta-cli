package de.gematik.zeta.cli.inspect

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** OAuth Authorization Server Metadata, RFC 8414. */
@Serializable
data class AuthorizationServer(
    val issuer: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String? = null,
    @SerialName("token_endpoint") val tokenEndpoint: String? = null,
    @SerialName("jwks_uri") val jwksUri: String? = null,
    @SerialName("registration_endpoint") val registrationEndpoint: String? = null,
    @SerialName("revocation_endpoint") val revocationEndpoint: String? = null,
    @SerialName("introspection_endpoint") val introspectionEndpoint: String? = null,
    @SerialName("scopes_supported") val scopesSupported: List<String> = emptyList(),
    @SerialName("response_types_supported") val responseTypesSupported: List<String> = emptyList(),
    @SerialName("response_modes_supported") val responseModesSupported: List<String> = emptyList(),
    @SerialName("grant_types_supported") val grantTypesSupported: List<String> = emptyList(),
    @SerialName("token_endpoint_auth_methods_supported")
    val tokenEndpointAuthMethodsSupported: List<String> = emptyList(),
    @SerialName("token_endpoint_auth_signing_alg_values_supported")
    val tokenEndpointAuthSigningAlgValuesSupported: List<String> = emptyList(),
    @SerialName("code_challenge_methods_supported")
    val codeChallengeMethodsSupported: List<String> = emptyList(),
    @SerialName("service_documentation") val serviceDocumentation: String? = null,
    @SerialName("op_policy_uri") val opPolicyUri: String? = null,
    @SerialName("op_tos_uri") val opTosUri: String? = null,
)
