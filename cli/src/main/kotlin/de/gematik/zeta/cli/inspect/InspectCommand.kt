package de.gematik.zeta.cli.inspect

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import de.gematik.zeta.api.AuthorizationServer
import de.gematik.zeta.api.AuthorizationServerClient
import de.gematik.zeta.api.ProtectedResource
import de.gematik.zeta.api.ProtectedResourceClient
import de.gematik.zeta.cli.ZetaCliktCommand
import de.gematik.zeta.cli.output.OutputFormat
import de.gematik.zeta.cli.output.renderJson
import de.gematik.zeta.cli.output.renderSections
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
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
        val httpClient = cliConfig.httpClient

        val (resource, authServer) = runBlocking { fetchAll(httpClient) }

        when (cliConfig.outputFormat) {
            OutputFormat.JSON -> {
                val payload = buildJsonObject {
                    put("oauth-protected-resource", resource.raw)
                    if (authServer != null) put("oauth-authorization-server", authServer.raw)
                }
                echo(renderJson(payload, colorize = colorize))
            }
            OutputFormat.TEXT -> echo(renderTextLayout(resource, authServer))
        }
    }

    private suspend fun fetchAll(httpClient: HttpClient): Pair<ProtectedResource, AuthorizationServer?> {
        val resource = ProtectedResourceClient(httpClient).fetch(url)
        val issuer = resource.authorizationServers.firstOrNull()
        val authServer = issuer?.let { iss ->
            runCatching { AuthorizationServerClient(httpClient).fetch(iss) }
                .onFailure { log.warn(it) { "Could not fetch authorization-server metadata from $iss" } }
                .getOrNull()
        }
        return resource to authServer
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
}
