package de.gematik.zeta.cli.status

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.theme
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import de.gematik.zeta.cli.client.ZetaSessionCommand
import de.gematik.zeta.cli.client.originOf
import de.gematik.zeta.cli.output.OutputFormat
import de.gematik.zeta.cli.output.renderJson
import de.gematik.zeta.cli.output.renderSections
import de.gematik.zeta.cli.storage.JsonFileStorage
import de.gematik.zeta.cli.storage.zetaProfilePath
import de.gematik.zeta.sdk.SdkStatus
import de.gematik.zeta.sdk.authentication.AuthenticationStorage
import de.gematik.zeta.sdk.authentication.AuthenticationStorageImpl
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationStorage
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationStorageImpl
import de.gematik.zeta.sdk.configuration.ConfigurationStorage
import de.gematik.zeta.sdk.configuration.ConfigurationStorageImpl
import de.gematik.zeta.sdk.storage.ExtendedStorage
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

private val log = KotlinLogging.logger {}

/**
 * `zeta status [URL]` — local view of the SDK's auth state for the active profile.
 *
 * Two modes:
 *  - **With URL**: go through the public SDK surface (`ZetaSdk.build(URL).status()`). Pays
 *    for full SDK construction (TPM init, `SubjectTokenProvider` build, Connector session
 *    open) because the SDK contract demands a complete `BuildConfig` regardless. Scopes
 *    are not consulted by `status()` and we pass an empty list.
 *  - **Without URL**: enumerate every cached resource and replicate `status()`'s logic
 *    against the storage layer. The SDK has no public "list resources" API, but the
 *    storage convention is public: read `ConfigurationStorageImpl.RESOURCE_INDEX_KEY` via
 *    `ExtendedStorage` to get the set of resource FQDNs. No SDK build, no auth flags
 *    needed (their values, if passed, are ignored).
 *
 * Both modes share the same upstream bug: `ZetaSdkClient.status()` looks up tokens by
 * `authServer.issuer` while `AccessTokenProviderImpl` saves them by the build-time
 * resource URL, so `status()` under-reports. The CLI mirrors the SDK behaviour faithfully.
 */
class StatusCommand : ZetaSessionCommand(name = "status") {
    private val url: String? by argument(
        name = "URL",
        help = "URL of a Zeta-protected resource. Without it, lists status for every " +
            "cached resource in the active profile.",
    ).optional()

    override fun help(context: Context) =
        "Show the SDK auth state (registration + token validity) for a single resource " +
            "or for every cached resource in the profile."

    override fun runCommand() {
        val target = url
        if (target != null) {
            singleResource(target)
        } else {
            allCachedResources()
        }
    }

    private fun singleResource(target: String) {
        val resource = originOf(target)
        log.debug { "Querying SDK status for resource $resource" }

        openSession(resource = resource, scopes = emptyList()) { sdk, _ ->
            val status = runBlocking { sdk.status().getOrThrow() }
            renderSingle(Entry(resource = resource, issuer = null, status = status))
        }
    }

    private fun allCachedResources() {
        val path = zetaProfilePath(profile)
        log.debug { "Enumerating resources from $path" }

        val storage = JsonFileStorage(path)
        val cfg = ConfigurationStorageImpl(storage)
        val reg = ClientRegistrationStorageImpl(storage)
        val auth = AuthenticationStorageImpl(storage)

        val entries = runBlocking { enumerateEntries(storage, cfg, reg, auth) }
        renderProfile(entries, path)
    }

    private fun renderSingle(entry: Entry) {
        when (cliConfig.outputFormat) {
            OutputFormat.JSON -> echo(renderJson(entry.toJson(), colorize = colorize))
            OutputFormat.TEXT, OutputFormat.RAW -> echo(
                renderSections(colorize = colorize) {
                    section("Status") {
                        field("Resource", entry.resource)
                        field("Authorization server", entry.issuer)
                        field("Status", entry.status.name)
                    }
                },
            )
        }
    }

    private fun renderProfile(entries: List<Entry>, path: Path) {
        when (cliConfig.outputFormat) {
            OutputFormat.JSON -> echo(
                renderJson(
                    buildJsonObject {
                        put("profile", JsonPrimitive(profile))
                        put("path", JsonPrimitive(path.toString()))
                        put("resources", buildJsonArray { entries.forEach { add(it.toJson()) } })
                    },
                    colorize = colorize,
                ),
            )

            OutputFormat.TEXT, OutputFormat.RAW -> echo(profileText(entries, path))
        }
    }

    private fun profileText(entries: List<Entry>, path: Path): String = buildString {
        appendLine(
            renderSections(colorize = colorize) {
                section("Profile") {
                    field("Profile", profile)
                    field("Path", path.toString())
                }
            },
        )
        if (entries.isEmpty()) {
            val msg = "No cached resources for this profile."
            appendLine(if (colorize) currentContext.theme.muted(msg) else msg)
            return@buildString
        }
        entries.forEach { entry ->
            appendLine(
                renderSections(colorize = colorize) {
                    section("Resource: ${entry.resource}") {
                        field("Authorization server", entry.issuer)
                        field("Status", entry.status.name)
                    }
                },
            )
        }
    }.trimEnd()
}

private data class Entry(
    val resource: String,
    val issuer: String?,
    val status: SdkStatus,
) {
    fun toJson(): JsonElement = buildJsonObject {
        put("resource", JsonPrimitive(resource))
        put("authorization_server", issuer?.let(::JsonPrimitive) ?: JsonNull)
        put("status", JsonPrimitive(status.name))
    }
}

/**
 * Walks the cached resources via the SDK's public storage convention:
 *
 *   `ConfigurationStorageImpl.RESOURCE_INDEX_KEY` → map of resFqdn → "present"
 *   `resource_by_fqdn:<fqdn>`                    → the protected-resource JSON (with .resource URL)
 *
 * Read through `ExtendedStorage` (public SDK class). The `ConfigurationStorage` interface
 * itself doesn't expose enumeration, so we use the index key directly — the constants are
 * public on `ConfigurationStorageImpl.Companion`.
 */
private suspend fun enumerateEntries(
    storage: de.gematik.zeta.sdk.storage.SdkStorage,
    cfg: ConfigurationStorage,
    reg: ClientRegistrationStorage,
    auth: AuthenticationStorage,
): List<Entry> {
    val ext = ExtendedStorage(storage)
    val resFqdns = ext.getMap(ConfigurationStorageImpl.RESOURCE_INDEX_KEY)?.keys ?: return emptyList()
    return resFqdns.map { fqdn -> entryFor(fqdn, cfg, reg, auth) }
}

/**
 * Replicates `ZetaSdkClient.status()`'s enum logic exactly, including the issuer-keyed
 * token lookup. Producing a different answer would diverge from what the SDK reports — we
 * want bug-for-bug parity until the upstream fix lands.
 *
 * `getAuthServer` and `getProtectedResource` normalise to the host via `hostOf`, so a
 * synthesized `https://$fqdn/` URL is sufficient for the lookup.
 */
private suspend fun entryFor(
    fqdn: String,
    cfg: ConfigurationStorage,
    reg: ClientRegistrationStorage,
    auth: AuthenticationStorage,
): Entry {
    val fqdnUrl = "https://$fqdn/"
    val resourceUrl = cfg.getProtectedResource(fqdnUrl)?.resource ?: fqdnUrl

    val authServer = cfg.getAuthServer(fqdnUrl)
        ?: return Entry(resourceUrl, null, SdkStatus.NOT_REGISTERED)
    reg.getRegistrationInfo(authServer.issuer)
        ?: return Entry(resourceUrl, authServer.issuer, SdkStatus.NOT_REGISTERED)

    val expiresAt = auth.getTokenExpiration(authServer.issuer)?.toLongOrNull() ?: 0L
    val nowEpoch = System.currentTimeMillis() / 1000
    val tokensExpired = expiresAt <= nowEpoch
    val access = auth.getAccessToken(authServer.issuer)
    val refresh = auth.getRefreshToken(authServer.issuer)
    val status = when {
        !access.isNullOrBlank() && !refresh.isNullOrBlank() && !tokensExpired ->
            SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN
        !refresh.isNullOrBlank() -> SdkStatus.HAS_REFRESH_TOKEN
        else -> SdkStatus.REGISTERED_NO_VALID_TOKENS
    }
    return Entry(resourceUrl, authServer.issuer, status)
}
