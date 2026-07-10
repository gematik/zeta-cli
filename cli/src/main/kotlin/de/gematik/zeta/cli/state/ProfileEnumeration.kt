package de.gematik.zeta.cli.state

import de.gematik.zeta.cli.storage.ProfileDb
import de.gematik.zeta.cli.storage.SqliteSdkStorage
import de.gematik.zeta.sdk.SdkStatus
import de.gematik.zeta.sdk.authentication.AuthenticationStorage
import de.gematik.zeta.sdk.authentication.AuthenticationStorageImpl
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationStorage
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationStorageImpl
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationResponse
import de.gematik.zeta.sdk.configuration.ConfigurationStorage
import de.gematik.zeta.sdk.configuration.ConfigurationStorageImpl
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.SdkStorage
import io.github.oshai.kotlinlogging.KotlinLogging
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
 * Adapter onto one resource scope's slice of a profile's SQLite storage. The three sub-storages
 * share a single [SqliteSdkStorage] bound to `scope`; all three are SDK publics, so the CLI
 * reuses them rather than parsing keys directly.
 */
internal class ProfileStores(val scope: ResourceScope, db: ProfileDb) {
    private val storage: SdkStorage = SqliteSdkStorage(scope.storageKey, db)
    val configuration: ConfigurationStorage = ConfigurationStorageImpl(storage, scope)
    val registration: ClientRegistrationStorage = ClientRegistrationStorageImpl(storage, scope)
    val authentication: AuthenticationStorage = AuthenticationStorageImpl(storage, scope)
}

/**
 * One [Entry] per resource scope recorded in the profile. zeta-sdk 1.2.2 dropped its resource
 * index, so the CLI keeps its own registry ([ProfileDb.contexts]); each scope is read back
 * through its own scope-bound [ProfileStores].
 */
internal suspend fun enumerateEntries(db: ProfileDb): List<Entry> =
    db.contexts().map { scope -> entryFor(ProfileStores(scope, db)) }

/**
 * Resolve one resource scope into a full [Entry]. Mirrors `ZetaSdkClient.status()`: the storages
 * are already bound to the scope (getters take no resource argument), and registration is looked
 * up by the AS registration endpoint, falling back to the issuer.
 */
internal suspend fun entryFor(stores: ProfileStores): Entry {
    val resourceUrl = stores.configuration.getProtectedResource()?.resource ?: stores.scope.fqdn

    val authServer = stores.configuration.getAuthServer()
        ?: return Entry(resourceUrl, null, SdkStatus.NOT_REGISTERED, null, null)

    val regKey = authServer.registrationEndpoint?.takeIf { it.isNotBlank() } ?: authServer.issuer
    val regResponse = stores.registration.getRegistrationInfo(regKey)
        ?: return Entry(resourceUrl, authServer.issuer, SdkStatus.NOT_REGISTERED, null, null)

    val expiresAt = stores.authentication.getTokenExpiration()?.toLongOrNull() ?: 0L
    val nowEpoch = System.currentTimeMillis() / 1000
    val tokensExpired = expiresAt <= nowEpoch
    val accessJwt = stores.authentication.getAccessToken()
    val refreshPresent = !stores.authentication.getRefreshToken().isNullOrBlank()

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
