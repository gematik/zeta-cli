package de.gematik.zeta.cli.lifecycle

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import de.gematik.zeta.cli.ZetaProfileCommand
import de.gematik.zeta.cli.client.originOf
import de.gematik.zeta.cli.output.OutputFormat
import de.gematik.zeta.cli.output.renderJson
import de.gematik.zeta.cli.output.renderSections
import de.gematik.zeta.cli.sdk.NoopSubjectTokenProvider
import de.gematik.zeta.cli.sdk.buildZetaSdkClient
import de.gematik.zeta.cli.state.ProfileStores
import de.gematik.zeta.cli.storage.zetaProfilePath
import de.gematik.zeta.sdk.ZetaSdkClientExtension
import de.gematik.zeta.sdk.configuration.models.ApiVersion
import de.gematik.zeta.sdk.configuration.models.AuthorizationServerMetadata
import de.gematik.zeta.sdk.configuration.models.ProtectedResourceMetadata
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

private val log = KotlinLogging.logger {}

/**
 * `zeta discover URL` — run the SDK's `discover()` flow against the resource origin,
 * persisting the protected-resource + authorization-server metadata under the active
 * profile, then display everything the SDK extracted from the well-known documents.
 *
 * Auth options are not required — the discover flow only triggers
 * `FlowNeed.ConfigurationFiles`, which never invokes `createSubjectToken`. A
 * [NoopSubjectTokenProvider] is wired so the SDK fails loudly if that ever changes.
 */
class DiscoverCommand : ZetaProfileCommand(name = "discover") {
    private val url: String by argument(
        name = "URL",
        help = "URL of the Zeta-protected resource. Only the scheme + host + port are used " +
            "(RFC 9728 fixes the well-known doc at the host root).",
    )

    override fun help(context: Context) =
        "Fetch and cache the protected-resource + authorization-server metadata for the given resource."

    override fun runCommand() {
        val resource = originOf(url)
        log.info { "Discovering $resource" }

        val sdk = buildZetaSdkClient(
            resource = resource,
            scopes = emptyList(),
            storagePath = zetaProfilePath(profile),
            tokenProvider = NoopSubjectTokenProvider,
            cliConfig = cliConfig,
        )
        try {
            val ok = ZetaSdkClientExtension.discover(sdk)
            if (!ok) error("SDK discover() returned false for $resource")
        } finally {
            ZetaSdkClientExtension.close(sdk)
        }

        val stores = ProfileStores(zetaProfilePath(profile))
        val (resourceMeta, asMeta) = runBlocking {
            val pr = stores.configuration.getProtectedResource(resource)
                ?: error("discover() succeeded but no protected-resource metadata cached for $resource")
            val asUrl = pr.authorizationServers.firstOrNull()
            val asm = asUrl?.let { stores.configuration.getAuthServer(resource) }
            pr to asm
        }

        render(resourceMeta, asMeta)
    }

    private fun render(resource: ProtectedResourceMetadata, authServer: AuthorizationServerMetadata?) {
        when (cliConfig.outputFormat) {
            OutputFormat.JSON -> echo(renderJson(toJsonEnvelope(resource, authServer), colorize = colorize))
            OutputFormat.TEXT, OutputFormat.RAW -> echo(renderText(resource, authServer))
        }
    }

    private fun renderText(
        resource: ProtectedResourceMetadata,
        authServer: AuthorizationServerMetadata?,
    ): String = renderSections(colorize = colorize) {
        section("Protected Resource") {
            field("Resource", resource.resource)
            field("Resource name", resource.resourceName)
            field("Authorization servers", resource.authorizationServers.orEmpty())
            field("Zeta ASL use", resource.zetaAslUse.name)
            field("JWKS URI", resource.jwksUri)
            field("Scopes supported", resource.scopesSupported.orEmpty())
            field("Bearer methods", resource.bearerMethodsSupported?.map { it.name }.orEmpty())
            field("Resource signing algs", resource.resourceSigningAlgValuesSupported.orEmpty())
            field("Authorization details types", resource.authorizationDetailsTypesSupported.orEmpty())
            field("DPoP signing algs", resource.dpopSigningAlgValuesSupported.orEmpty())
            field("DPoP bound tokens required", resource.dpopBoundAccessTokensRequired.toString())
            field("mTLS client cert bound tokens", resource.tlsClientCertificateBoundAccessTokens.toString())
            field("API versions", resource.apiVersionsSupported?.map(::formatApiVersion).orEmpty())
            field("Documentation", resource.resourceDocumentation)
            field("Policy", resource.resourcePolicyUri)
            field("ToS", resource.resourceTosUri)
            field("Signed metadata", resource.signedMetadata)
        }
        if (authServer != null) {
            section("Authorization Server") {
                field("Issuer", authServer.issuer)
                field("Authorization endpoint", authServer.authorizationEndpoint)
                field("Token endpoint", authServer.tokenEndpoint)
                field("Nonce endpoint", authServer.nonceEndpoint)
                field("OpenID providers endpoint", authServer.openidProvidersEndpoint)
                field("JWKS URI", authServer.jwksUri)
                field("Scopes supported", authServer.scopesSupported.orEmpty())
                field("Response types", authServer.responseTypesSupported.orEmpty())
                field("Response modes", authServer.responseModesSupported.orEmpty())
                field("Grant types", authServer.grantTypesSupported.orEmpty())
                field("Token auth methods", authServer.tokenEndpointAuthMethodsSupported.orEmpty())
                field("Token auth signing algs", authServer.tokenEndpointAuthSigningAlgValuesSupported.orEmpty())
                field("Code challenge methods", authServer.codeChallengeMethodsSupported.orEmpty())
                field("UI locales", authServer.uiLocalesSupported.orEmpty())
                field("API versions", authServer.apiVersionsSupported?.map(::formatApiVersion).orEmpty())
                field("Documentation", authServer.serviceDocumentation)
            }
        }
    }

    private fun toJsonEnvelope(
        resource: ProtectedResourceMetadata,
        authServer: AuthorizationServerMetadata?,
    ) = buildJsonObject {
        put("oauth-protected-resource", json.encodeToJsonElement(resource))
        put(
            "oauth-authorization-server",
            authServer?.let { json.encodeToJsonElement(it) } ?: JsonNull,
        )
    }

    private companion object {
        // The SDK's metadata classes are @Serializable; encodeDefaults = false keeps
        // empty lists / nulls out of the JSON for a tighter view.
        val json = Json {
            encodeDefaults = false
            explicitNulls = false
        }
    }
}

private fun formatApiVersion(v: ApiVersion): String {
    val docs = v.documentationUri?.takeIf { it.isNotBlank() }
    val base = "${v.version} (${v.status.name.lowercase()})"
    return if (docs != null) "$base — $docs" else base
}
