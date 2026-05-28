package de.gematik.zeta.cli.state

import de.gematik.zeta.cli.output.renderSections
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Renderers for [Entry] used by `status` and the lifecycle commands (`register`,
 * `authenticate`, `login`, `logout`). All four print the same shape of "what's in storage
 * now" — only the side effect of getting there differs.
 *
 * `reveal` toggles secret exposure: the raw access-token JWS, the dynamic-client
 * `client_secret`, and the `registration_access_token`. Refresh tokens are never emitted
 * regardless — their presence informs the status enum and that's all.
 */
internal fun renderEntryText(entry: Entry, colorize: Boolean, reveal: Boolean): String =
    renderSections(colorize = colorize) {
        val nowEpoch = System.currentTimeMillis() / 1000
        val expiredFor = entry.accessToken?.expiresAt
            ?.takeIf { it in 1..nowEpoch }
            ?.let { nowEpoch - it }
        val statusValue = entry.status.name + (expiredFor?.let { " (access token expired ${formatAgo(it)} ago)" } ?: "")
        section("Resource: ${entry.resource}") {
            field("Authorization server", entry.issuer)
            field("Status", statusValue)
        }
        entry.accessToken?.let { token ->
            section("Access token (resource: ${entry.resource})") {
                field("Algorithm", token.headerString("alg"))
                field("Type", token.headerString("typ"))
                field("Key ID", token.headerString("kid"))
                field("Issuer", token.claimString("iss"))
                field("Subject", token.claimString("sub"))
                field("Audience", token.claimStrings("aud"))
                field("Scope", token.claimString("scope"))
                field("DPoP jkt", token.claimString("cnf", "jkt"))
                field(
                    "Expires at",
                    token.expiresAt.takeIf { it > 0 }
                        ?.let { formatEpoch(it) + if (it <= nowEpoch) " (expired)" else "" },
                )
                if (reveal) field("Raw JWS", token.rawJwt)
            }
        }
        entry.registration?.let { reg ->
            section("Registration (auth server: ${entry.issuer ?: "?"})") {
                field("Client ID", reg.clientId)
                field("Client name", reg.clientName)
                field("Token-endpoint auth", reg.tokenEndpointAuthMethod)
                field("Grant types", reg.grantTypes)
                field("Response types", reg.responseTypes)
                field("Redirect URIs", reg.redirectUris)
                field("Scope", reg.scope)
                field("JWKS keys", reg.jwksKeyCount)
                field("Issued at", reg.clientIdIssuedAt?.let(::formatEpoch))
                field("Secret expires", reg.clientSecretExpiresAt?.takeIf { it > 0 }?.let(::formatEpoch))
                if (reveal) {
                    field("Client secret", reg.raw.clientSecret)
                    field("Registration access token", reg.raw.registrationAccessToken)
                }
            }
        }
    }

internal fun renderEntryJson(entry: Entry, reveal: Boolean): JsonElement = buildJsonObject {
    put("resource", JsonPrimitive(entry.resource))
    put("authorization_server", entry.issuer?.let(::JsonPrimitive) ?: JsonNull)
    put("status", JsonPrimitive(entry.status.name))
    put(
        "access_token",
        entry.accessToken?.let { token ->
            buildJsonObject {
                put("header", token.header)
                put("claims", token.claims)
                put("expires_at", JsonPrimitive(token.expiresAt))
                if (reveal) put("raw", JsonPrimitive(token.rawJwt))
            }
        } ?: JsonNull,
    )
    put(
        "registration",
        entry.registration?.let { reg ->
            buildJsonObject {
                put("client_id", reg.clientId?.let(::JsonPrimitive) ?: JsonNull)
                put("client_name", reg.clientName?.let(::JsonPrimitive) ?: JsonNull)
                put("token_endpoint_auth_method", reg.tokenEndpointAuthMethod?.let(::JsonPrimitive) ?: JsonNull)
                put("grant_types", buildJsonArray { reg.grantTypes.forEach { add(JsonPrimitive(it)) } })
                put("response_types", buildJsonArray { reg.responseTypes.forEach { add(JsonPrimitive(it)) } })
                put("redirect_uris", buildJsonArray { reg.redirectUris.forEach { add(JsonPrimitive(it)) } })
                put("scope", reg.scope?.let(::JsonPrimitive) ?: JsonNull)
                put("jwks_keys", JsonPrimitive(reg.jwksKeyCount))
                put("client_id_issued_at", reg.clientIdIssuedAt?.let(::JsonPrimitive) ?: JsonNull)
                put(
                    "client_secret_expires_at",
                    reg.clientSecretExpiresAt?.takeIf { it > 0 }?.let(::JsonPrimitive) ?: JsonNull,
                )
                if (reveal) {
                    put("client_secret", reg.raw.clientSecret?.let(::JsonPrimitive) ?: JsonNull)
                    put(
                        "registration_access_token",
                        reg.raw.registrationAccessToken?.let(::JsonPrimitive) ?: JsonNull,
                    )
                }
            }
        } ?: JsonNull,
    )
}

private fun TokenInfo.headerString(name: String): String? =
    (header[name] as? JsonPrimitive)?.contentOrNull()

private fun TokenInfo.claimString(name: String): String? =
    (claims[name] as? JsonPrimitive)?.contentOrNull()

private fun TokenInfo.claimString(name: String, nested: String): String? {
    val obj = claims[name] as? JsonObject ?: return null
    return (obj[nested] as? JsonPrimitive)?.contentOrNull()
}

private fun TokenInfo.claimStrings(name: String): List<String> = when (val node = claims[name]) {
    is JsonPrimitive -> node.contentOrNull()?.let(::listOf).orEmpty()
    is JsonArray -> node.mapNotNull { (it as? JsonPrimitive)?.contentOrNull() }
    else -> emptyList()
}

private fun JsonPrimitive.contentOrNull(): String? = if (this is JsonNull) null else content

private fun formatEpoch(seconds: Long): String =
    java.time.Instant.ofEpochSecond(seconds).toString()

private fun formatAgo(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m"
    seconds < 86400 -> "${seconds / 3600}h"
    else -> "${seconds / 86400}d"
}
