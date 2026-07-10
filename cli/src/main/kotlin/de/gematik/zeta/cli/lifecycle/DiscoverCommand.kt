package de.gematik.zeta.cli.lifecycle

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import de.gematik.zeta.cli.ZetaProfileCommand
import de.gematik.zeta.cli.client.originOf
import de.gematik.zeta.cli.output.OutputFormat
import de.gematik.zeta.cli.output.renderJson
import de.gematik.zeta.cli.sdk.NoopSubjectTokenProvider
import de.gematik.zeta.cli.sdk.buildZetaSdkClient
import de.gematik.zeta.cli.state.CommandResult
import de.gematik.zeta.cli.state.ProfileStores
import de.gematik.zeta.cli.state.renderResultText
import de.gematik.zeta.cli.storage.ProfileDb
import de.gematik.zeta.cli.storage.zetaProfilePath
import de.gematik.zeta.sdk.ZetaSdkClientExtension
import de.gematik.zeta.sdk.configuration.models.AuthorizationServerMetadata
import de.gematik.zeta.sdk.configuration.models.ProtectedResourceMetadata
import de.gematik.zeta.sdk.storage.ResourceScope
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

        val stores = ProfileStores(ResourceScope(resource, emptyList()), ProfileDb(zetaProfilePath(profile)))
        val (resourceMeta, asMeta) = runBlocking {
            val pr = stores.configuration.getProtectedResource()
                ?: error("discover() succeeded but no protected-resource metadata cached for $resource")
            val asUrl = pr.authorizationServers.firstOrNull()
            val asm = asUrl?.let { stores.configuration.getAuthServer() }
            pr to asm
        }

        render(resourceMeta, asMeta)
    }

    private fun render(resource: ProtectedResourceMetadata, authServer: AuthorizationServerMetadata?) {
        when (cliConfig.outputFormat) {
            OutputFormat.JSON -> echo(renderJson(toJsonEnvelope(resource, authServer), colorize = colorize))
            OutputFormat.TEXT, OutputFormat.RAW -> echo(
                renderResultText(
                    CommandResult(
                        operation = "discover",
                        ok = authServer != null,
                        endpoint = resource.resource,
                        scopes = resource.scopesSupported.orEmpty(),
                        authServer = authServer?.issuer,
                        status = null,
                        detail = if (authServer == null) "no authorization server advertised" else null,
                    ),
                    colorize,
                ),
            )
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
