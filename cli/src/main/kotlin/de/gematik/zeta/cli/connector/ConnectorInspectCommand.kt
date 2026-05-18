package de.gematik.zeta.cli.connector

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import de.gematik.connector.ConnectorException
import de.gematik.connector.ConnectorServices
import de.gematik.connector.Credentials
import de.gematik.connector.Dotkon
import de.gematik.connector.ConnectorClient
import de.gematik.connector.engine.okhttp.dotkonOkHttpClient
import de.gematik.zeta.cli.http.applyProxy
import de.gematik.zeta.cli.http.applyProxyAuthenticator
import de.gematik.connector.parseDotkon
import de.gematik.zeta.cli.ZetaCliktCommand
import de.gematik.zeta.cli.http.installCurlieLogging
import de.gematik.zeta.cli.output.OutputFormat
import de.gematik.zeta.cli.output.renderJson
import de.gematik.zeta.cli.output.renderSections
import de.gematik.zeta.cli.trace.HttpTracingPlugin
import de.gematik.zeta.cli.trace.Tracer
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.HttpTimeout
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val log = KotlinLogging.logger {}

class ConnectorInspectCommand : ZetaCliktCommand(name = "inspect") {
    override fun help(context: Context) =
        "Connect to a Connector described by a .kon file and display product / service information."

    override fun runCommand() {
        val configFile = try {
            resolveKonFile(cliConfig.connectorConfig)
        } catch (e: KonFileNotFoundException) {
            throw UsageError(e.message ?: "kon file not found")
        }
        try {
            doRun(configFile)
        } catch (e: CliktError) {
            // Already a Clikt-formatted error — let it propagate untouched.
            throw e
        } catch (e: ConnectorException) {
            // Expected failure mode (bad config, server-side error, SOAP fault) — the
            // message itself is the diagnostic. No stack trace, no info log.
            throw CliktError(e.message ?: e::class.simpleName ?: "connector inspect failed")
        } catch (e: Exception) {
            // Unexpected — keep the trace on the info log so it can be retrieved with -v.
            log.info(e) { "connector inspect failed" }
            throw CliktError(e.message ?: e::class.simpleName ?: "connector inspect failed")
        }
    }

    private fun doRun(configFile: Path) {
        log.info { "Reading .kon from $configFile" }
        val dotkon = parseDotkon(configFile.readText())
        log.info { "Connecting to Connector at ${dotkon.url}" }
        log.debug { "Mandant=${dotkon.mandantId} Workplace=${dotkon.workplaceId} ClientSystem=${dotkon.clientSystemId}" }

        // OkHttp engine for mutual TLS: Ktor CIO's TLS implementation drops the client
        // certificate whenever the server's CertificateRequest doesn't enumerate a matching
        // CA name, which most Connector-fronting nginx instances don't do. OkHttp routes
        // through JSSE and presents what's installed.
        val httpClient = dotkonOkHttpClient(dotkon) {
            install(HttpTimeout) {
                connectTimeoutMillis = cliConfig.connectTimeout.inWholeMilliseconds
                requestTimeoutMillis = cliConfig.requestTimeout.inWholeMilliseconds
            }
            // -vv (DEBUG on de.gematik.zeta.http.wire) toggles full curlie-style request/response
            // logging — same wire log format the rest of the CLI uses.
            installCurlieLogging()
            install(HttpTracingPlugin)
            engine {
                cliConfig.proxy?.let {
                    applyProxy(it)
                    applyProxyAuthenticator(it)
                }
            }
        }
        val connector = httpClient.use { http ->
            runBlocking { Tracer.spanSuspend("connector.connect") { ConnectorClient.connect(http, dotkon) } }
        }
        log.info { "Loaded ${connector.services.serviceInformation.service.size} services from $configFile" }

        when (cliConfig.outputFormat) {
            OutputFormat.JSON -> echo(renderJson(buildJsonReport(configFile, dotkon, connector.services), colorize = colorize))
            OutputFormat.TEXT, OutputFormat.RAW -> echo(renderTextReport(configFile, dotkon, connector.services))
        }
    }

    private fun buildJsonReport(configFile: Path, dotkon: Dotkon, services: ConnectorServices): JsonObject = buildJsonObject {
        put("configuration", redactedDotkonJson(dotkon, configFile))
        put("productInformation", buildJsonObject {
            val pti = services.productInformation.productTypeInformation
            put("productType", pti.productType)
            put("productTypeVersion", pti.productTypeVersion)
            val pid = services.productInformation.productIdentification
            put("vendor", pid.productVendorId)
            put("productCode", pid.productCode)
            put("hwVersion", pid.productVersion.local.hwVersion)
            put("fwVersion", pid.productVersion.local.fwVersion)
        })
        put("services", buildJsonArray {
            services.serviceInformation.service
                .sortedBy { it.name }
                .forEach { svc ->
                    addJsonObject {
                        put("name", svc.name)
                        put("versions", buildJsonArray {
                            svc.versions.version.forEach { ver ->
                                addJsonObject {
                                    put("version", ver.version)
                                    put("targetNamespace", ver.targetNamespace)
                                    ver.endpointTLS?.location?.let { put("endpointTLS", it) }
                                    ver.endpoint?.location?.let { put("endpoint", it) }
                                }
                            }
                        })
                    }
                }
        })
    }

    private fun renderTextReport(configFile: Path, dotkon: Dotkon, services: ConnectorServices): String =
        renderSections(colorize = colorize) {
            section("Configuration") {
                field("File", configFile.toString())
                field("URL", dotkon.url)
                field("Environment", dotkon.env)
                field("Expected host", dotkon.expectedHost)
                field("Mandant", dotkon.mandantId)
                field("Workplace", dotkon.workplaceId)
                field("Client system", dotkon.clientSystemId)
                field("User", dotkon.userId)
                field("Credentials", credentialsLabel(dotkon.credentials))
                if (dotkon.trustStore.isNotEmpty()) {
                    field("Trust store", "${dotkon.trustStore.size} certificate(s)")
                }
                if (dotkon.insecureSkipVerify) field("Insecure skip verify", "true")
                if (dotkon.rewriteServiceEndpoints) field("Rewrite endpoints", "true")
            }

            val pti = services.productInformation.productTypeInformation
            val pid = services.productInformation.productIdentification
            section("Product Information") {
                field("Product type", pti.productType)
                field("Product type version", pti.productTypeVersion)
                field("Vendor", pid.productVendorId)
                field("Product code", pid.productCode)
                field("Hardware version", pid.productVersion.local.hwVersion)
                field("Firmware version", pid.productVersion.local.fwVersion)
            }

            services.serviceInformation.service
                .sortedBy { it.name }
                .forEach { svc ->
                    section(svc.name) {
                        svc.versions.version.forEach { ver ->
                            val endpoint = ver.endpointTLS?.location ?: ver.endpoint?.location ?: "(no endpoint)"
                            field(ver.version, endpoint)
                        }
                    }
                }
        }
}

/**
 * Build a [JsonObject] view of [dotkon] suitable for display.
 *
 * Secrets are stripped: passwords, PKCS#12 PFX bytes, and the raw base64 trust-store
 * entries are omitted. Identifying-but-non-secret fields (username, mandant ids, etc.)
 * are kept. The trust store appears only as a count.
 */
private fun redactedDotkonJson(dotkon: Dotkon, sourceFile: Path): JsonObject = buildJsonObject {
    put("file", sourceFile.toString())
    dotkon.version?.let { put("version", it) }
    put("url", dotkon.url)
    dotkon.env?.let { put("env", it) }
    dotkon.expectedHost?.let { put("expectedHost", it) }
    put("mandantId", dotkon.mandantId)
    put("workplaceId", dotkon.workplaceId)
    put("clientSystemId", dotkon.clientSystemId)
    dotkon.userId?.let { put("userId", it) }
    put("credentials", buildJsonObject {
        when (val c = dotkon.credentials) {
            is Credentials.Basic -> {
                put("type", "basic")
                put("username", c.username)
                // password deliberately omitted
            }
            is Credentials.Pkcs12 -> {
                put("type", "pkcs12")
                // data + password deliberately omitted
            }
        }
    })
    if (dotkon.trustStore.isNotEmpty()) put("trustStoreSize", dotkon.trustStore.size)
    if (dotkon.insecureSkipVerify) put("insecureSkipVerify", true)
    if (dotkon.rewriteServiceEndpoints) put("rewriteServiceEndpoints", true)
}

private fun credentialsLabel(credentials: Credentials): String = when (credentials) {
    is Credentials.Basic -> "basic (username=${credentials.username})"
    is Credentials.Pkcs12 -> "pkcs12 client certificate"
}
