package de.gematik.zeta.api

data class OAuth2Client(
    val clientId: String,
    val clientName: String? = null,
    val redirectUris: List<String> = emptyList(),
    val grantTypes: List<String> = emptyList(),
)
