package de.gematik.zeta.cli.state

import de.gematik.zeta.cli.storage.JsonFileStorage
import de.gematik.zeta.sdk.SdkStatus
import de.gematik.zeta.sdk.authentication.AuthenticationStorage
import de.gematik.zeta.sdk.authentication.AuthenticationStorageImpl
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationStorage
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationStorageImpl
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationResponse
import de.gematik.zeta.sdk.configuration.ConfigurationStorage
import de.gematik.zeta.sdk.configuration.ConfigurationStorageImpl
import de.gematik.zeta.sdk.configuration.models.AuthorizationServerMetadata
import de.gematik.zeta.sdk.storage.ExtendedStorage
import de.gematik.zeta.sdk.storage.SdkStorage
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val log = KotlinLogging.logger {}

/**
 * Full denormalised view of one Zeta-protected resource cached in a profile's storage:
 * link to the authorization server, current [SdkStatus], decoded access-token claims, and
 * the dynamic-client-registration response. Built by [entryFor]; rendered by status (and
 * future read-only commands).
 *
 * `accessToken` and `registration` are populated independently — a resource might have a
 * cached registration but no current token (`REGISTERED_NO_VALID_TOKENS`), or the AS link
 * but no registration yet (`NOT_REGISTERED`). Renderers must handle each as optional.
 *
 * The refresh token itself is intentionally **not** stored on [Entry] — only its presence
 * informs the [SdkStatus] enum. Code that consumes [Entry] therefore cannot leak it to
 * output. The raw access-token string is kept for `--reveal` callers that want to display
 * the full payload body; renderers default to showing only header + claims.
 */
internal data class Entry(
    val resource: String,
    val issuer: String?,
    val status: SdkStatus,
    val accessToken: TokenInfo?,
    val registration: RegistrationInfo?,
)

internal data class TokenInfo(
    val rawJwt: String,
    val header: JsonObject,
    val claims: JsonObject,
    val expiresAt: Long,
)

internal data class RegistrationInfo(
    val raw: ClientRegistrationResponse,
) {
    val clientId: String? get() = raw.clientId
    val clientIdIssuedAt: Long? get() = raw.clientIdIssuedAt
    val clientSecretExpiresAt: Long? get() = raw.clientSecretExpiresAt
    val redirectUris: List<String> get() = raw.redirectUris
    val grantTypes: List<String> get() = raw.grantTypes
    val responseTypes: List<String> get() = raw.responseTypes
    val tokenEndpointAuthMethod: String? get() = raw.tokenEndpointAuthMethod
    val scope: String? get() = raw.scope
    val clientName: String? get() = raw.clientName
    val jwksKeyCount: Int get() = raw.jwks?.keys?.size ?: 0
}

/**
 * Adapter onto a profile's on-disk storage file. The four sub-storages share a single
 * [JsonFileStorage] so reads see a consistent snapshot. All four interfaces are SDK
 * publics — the CLI re-uses them rather than parsing keys directly.
 */
internal class ProfileStores(path: Path) {
    val storage: SdkStorage = JsonFileStorage(path)
    val configuration: ConfigurationStorage = ConfigurationStorageImpl(storage)
    val registration: ClientRegistrationStorage = ClientRegistrationStorageImpl(storage)
    val authentication: AuthenticationStorage = AuthenticationStorageImpl(storage)
}

/**
 * List every resource cached in the profile. Uses [ConfigurationStorageImpl.RESOURCE_INDEX_KEY]
 * to enumerate FQDNs — the public `ConfigurationStorage` interface itself doesn't expose
 * enumeration, so we go one layer deeper via [ExtendedStorage]. The two index/prefix
 * constants are public on the companion, so this isn't reaching into internal state.
 */
internal suspend fun enumerateEntries(stores: ProfileStores): List<Entry> {
    val ext = ExtendedStorage(stores.storage)
    val resFqdns = ext.getMap(ConfigurationStorageImpl.RESOURCE_INDEX_KEY)?.keys ?: return emptyList()
    return resFqdns.map { entryFor(it, stores) }
}

/**
 * Resolve one resource (by host FQDN) into a full [Entry]. Mirrors `ZetaSdkClient.status()`
 * in SDK 1.0.1+: tokens are keyed by the build-time resource URL (what
 * `AccessTokenProviderImpl` saves under), while registration is keyed by the AS issuer.
 *
 * `getAuthServer` / `getProtectedResource` normalise the input to the host, so a
 * synthesised `https://$fqdn/` is sufficient. Tokens saved with a non-default port at SDK
 * build time will not be found by this enumeration since the resource index drops the
 * port — that's an unavoidable side-effect of the FQDN-keyed index.
 */
internal suspend fun entryFor(fqdn: String, stores: ProfileStores): Entry {
    val fqdnUrl = "https://$fqdn/"
    val resourceUrl = stores.configuration.getProtectedResource(fqdnUrl)?.resource ?: fqdnUrl

    val authServer = stores.configuration.getAuthServer(fqdnUrl)
        ?: return Entry(resourceUrl, null, SdkStatus.NOT_REGISTERED, null, null)

    val regResponse = stores.registration.getRegistrationInfo(authServer.issuer)
        ?: return Entry(resourceUrl, authServer.issuer, SdkStatus.NOT_REGISTERED, null, null)

    val expiresAt = stores.authentication.getTokenExpiration(fqdnUrl)?.toLongOrNull() ?: 0L
    val nowEpoch = System.currentTimeMillis() / 1000
    val tokensExpired = expiresAt <= nowEpoch
    val accessJwt = stores.authentication.getAccessToken(fqdnUrl)
    val refreshPresent = !stores.authentication.getRefreshToken(fqdnUrl).isNullOrBlank()

    val status = when {
        !accessJwt.isNullOrBlank() && refreshPresent && !tokensExpired ->
            SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN
        refreshPresent -> SdkStatus.HAS_REFRESH_TOKEN
        else -> SdkStatus.REGISTERED_NO_VALID_TOKENS
    }

    val tokenInfo = accessJwt?.takeIf { it.isNotBlank() }?.let { decodeJwt(it, expiresAt) }

    return Entry(
        resource = resourceUrl,
        issuer = authServer.issuer,
        status = status,
        accessToken = tokenInfo,
        registration = RegistrationInfo(regResponse),
    )
}

/**
 * Best-effort JWT decode: split on '.', base64url-decode header and claims, parse as
 * JSON. Returns null (rather than throwing) when the token isn't a parseable JWS so the
 * surrounding [Entry] still renders the rest of the cached state. Signature verification
 * is intentionally skipped — this is a display, not a security check.
 */
private fun decodeJwt(jwt: String, expiresAt: Long): TokenInfo? {
    val parts = jwt.split('.')
    if (parts.size < 2) return null
    return try {
        val decoder = Base64.getUrlDecoder()
        val header = Json.parseToJsonElement(String(decoder.decode(parts[0]))) as? JsonObject ?: return null
        val claims = Json.parseToJsonElement(String(decoder.decode(parts[1]))) as? JsonObject ?: return null
        TokenInfo(rawJwt = jwt, header = header, claims = claims, expiresAt = expiresAt)
    } catch (e: Exception) {
        log.debug(e) { "JWT decode failed; treating token as opaque" }
        null
    }
}
