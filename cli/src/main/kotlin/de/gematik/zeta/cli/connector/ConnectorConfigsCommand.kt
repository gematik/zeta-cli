/*
 * Copyright (Change Date see Readme), gematik GmbH
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the
 * European Commission – subsequent versions of the EUPL (the "Licence").
 * You may not use this work except in compliance with the Licence.
 *
 * You find a copy of the Licence in the "Licence" file or at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.
 * In case of changes by gematik GmbH find details in the "Readme" file.
 *
 * See the Licence for the specific language governing permissions and limitations under the Licence.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 */
package de.gematik.zeta.cli.connector

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.mordant.rendering.TextStyles
import de.gematik.connector.parseDotkon
import de.gematik.zeta.cli.ZetaCliktCommand
import de.gematik.zeta.cli.output.OutputFormat
import de.gematik.zeta.cli.output.renderJson
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

private const val ACTIVE_MARKER = "*"
private const val INACTIVE_MARKER = " "

private val log = KotlinLogging.logger {}

class ConnectorConfigsCommand : ZetaCliktCommand(name = "configs") {
    override fun help(context: Context) =
        "List available .kon configuration files in the current directory and " +
            "\$XDG_CONFIG_HOME/telematik/connectors/. Marks the active config (set with " +
            "`zeta connector use`)."

    override fun runCommand() {
        val active = readActiveConnector()
        val configs = listKonConfigs().mapNotNull { path -> readConfig(path, active) }

        when (cliConfig.outputFormat) {
            OutputFormat.JSON -> echo(renderJson(jsonReport(configs), colorize = colorize))
            OutputFormat.TEXT, OutputFormat.RAW -> echo(textReport(configs))
        }
    }
}

private data class KonConfigInfo(
    val name: String,
    val url: String,
    val context: String,
    val path: Path,
    val active: Boolean,
)

private fun readConfig(path: Path, activeName: String?): KonConfigInfo? {
    val text = runCatching { path.readText() }.getOrElse {
        log.warn { "could not read $path: ${it.message}" }
        return null
    }
    val dk = runCatching { parseDotkon(text) }.getOrElse {
        log.warn { "could not parse $path: ${it.message}" }
        return null
    }
    val name = path.nameWithoutExtension
    return KonConfigInfo(
        name = name,
        url = dk.url,
        context = listOf(dk.mandantId, dk.workplaceId, dk.clientSystemId).joinToString("/"),
        path = path,
        active = activeName != null && activeName == name,
    )
}

private fun jsonReport(configs: List<KonConfigInfo>): JsonArray = buildJsonArray {
    configs.forEach { c ->
        addJsonObject {
            put("name", c.name)
            put("url", c.url)
            put("context", c.context)
            put("path", c.path.toString())
            put("active", c.active)
        }
    }
}

private fun textReport(configs: List<KonConfigInfo>): String {
    if (configs.isEmpty()) {
        return "No .kon configurations found in the current directory or ${connectorsConfigDir()}/."
    }
    val nameW = maxOf("NAME".length, configs.maxOf { it.name.length })
    val urlW = maxOf("URL".length, configs.maxOf { it.url.length })
    val header = TextStyles.bold("  ${"NAME".padEnd(nameW)}  ${"URL".padEnd(urlW)}  CONTEXT")
    return buildString {
        appendLine(header)
        configs.forEach { c ->
            val marker = if (c.active) ACTIVE_MARKER else INACTIVE_MARKER
            appendLine("$marker ${c.name.padEnd(nameW)}  ${c.url.padEnd(urlW)}  ${c.context}")
        }
    }.trimEnd()
}
