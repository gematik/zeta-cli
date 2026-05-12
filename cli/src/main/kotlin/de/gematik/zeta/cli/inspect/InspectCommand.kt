package de.gematik.zeta.cli.inspect

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import de.gematik.zeta.cli.ZetaCliktCommand
import de.gematik.zeta.cli.output.OutputFormat
import de.gematik.zeta.cli.output.renderJson
import de.gematik.zeta.cli.output.renderSections
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

private val log = KotlinLogging.logger {}

class InspectCommand : ZetaCliktCommand(name = "inspect") {
    private val url: String by argument(
        name = "URL",
        help = "Base URL of the Zeta-protected resource to inspect.",
    )

    override fun help(context: Context) =
        "Display all information about a resource protected by Zeta Guard."

    override fun runCommand() {
        log.info { "Inspecting protected resource at $url" }
        val http = cliConfig.httpClient

        val data = runBlocking {
            val (resource, resourceRaw) = fetchProtectedResource(http, url)
            val auth = resource.authorizationServers.firstOrNull()?.let { iss ->
                runCatching { fetchAuthorizationServer(http, iss) }
                    .onFailure { log.warn(it) { "Could not fetch authorization-server metadata from $iss" } }
                    .getOrNull()
            }
            InspectData(resource, resourceRaw, auth?.first, auth?.second)
        }

        when (cliConfig.outputFormat) {
            OutputFormat.JSON -> echo(renderJson(data.toJsonEnvelope(), colorize = colorize))
            OutputFormat.TEXT, OutputFormat.RAW -> echo(renderTextLayout(data.resource, data.authServer))
        }
    }

    private fun renderTextLayout(
        resource: ProtectedResource,
        authServer: AuthorizationServer?,
    ): String = renderSections(colorize = colorize) {
        section("Protected Resource") {
            field("Resource", resource.resource)
            field("Authorization servers", resource.authorizationServers)
            field("JWKS URI", resource.jwksUri)
            field("Scopes", resource.scopesSupported)
            field("Bearer methods", resource.bearerMethodsSupported)
            field("Signing algs", resource.resourceSigningAlgValuesSupported)
            field("Documentation", resource.resourceDocumentation)
            field("Policy", resource.resourcePolicyUri)
            field("ToS", resource.resourceTosUri)
        }
        if (authServer != null) {
            section("Authorization Server") {
                field("Issuer", authServer.issuer)
                field("Authorization endpoint", authServer.authorizationEndpoint)
                field("Token endpoint", authServer.tokenEndpoint)
                field("JWKS URI", authServer.jwksUri)
                field("Registration endpoint", authServer.registrationEndpoint)
                field("Revocation endpoint", authServer.revocationEndpoint)
                field("Introspection endpoint", authServer.introspectionEndpoint)
                field("Scopes supported", authServer.scopesSupported)
                field("Grant types", authServer.grantTypesSupported)
                field("Response types", authServer.responseTypesSupported)
                field("Response modes", authServer.responseModesSupported)
                field("Token auth methods", authServer.tokenEndpointAuthMethodsSupported)
                field("Code challenge methods", authServer.codeChallengeMethodsSupported)
                field("Documentation", authServer.serviceDocumentation)
                field("Policy", authServer.opPolicyUri)
                field("ToS", authServer.opTosUri)
            }
        }
    }

    private data class InspectData(
        val resource: ProtectedResource,
        val resourceRaw: JsonObject,
        val authServer: AuthorizationServer?,
        val authServerRaw: JsonObject?,
    ) {
        fun toJsonEnvelope() = buildJsonObject {
            put("oauth-protected-resource", resourceRaw)
            if (authServerRaw != null) put("oauth-authorization-server", authServerRaw)
        }
    }
}
