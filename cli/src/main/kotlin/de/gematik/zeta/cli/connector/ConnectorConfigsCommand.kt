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

private val log = KotlinLogging.logger {}

class ConnectorConfigsCommand : ZetaCliktCommand(name = "configs") {
    override fun help(context: Context) =
        "List available .kon configuration files in the current directory and \$XDG_CONFIG_HOME/telematik/kon/."

    override fun runCommand() {
        val configs = listKonConfigs().mapNotNull { path -> readConfig(path) }

        when (cliConfig.outputFormat) {
            OutputFormat.JSON -> echo(renderJson(jsonReport(configs), colorize = colorize))
            OutputFormat.TEXT -> echo(textReport(configs))
        }
    }
}

private data class KonConfigInfo(
    val name: String,
    val url: String,
    val context: String,
    val path: Path,
)

private fun readConfig(path: Path): KonConfigInfo? {
    val text = runCatching { path.readText() }.getOrElse {
        log.warn { "could not read $path: ${it.message}" }
        return null
    }
    val dk = runCatching { parseDotkon(text) }.getOrElse {
        log.warn { "could not parse $path: ${it.message}" }
        return null
    }
    return KonConfigInfo(
        name = path.nameWithoutExtension,
        url = dk.url,
        context = listOf(dk.mandantId, dk.workplaceId, dk.clientSystemId).joinToString("/"),
        path = path,
    )
}

private fun jsonReport(configs: List<KonConfigInfo>): JsonArray = buildJsonArray {
    configs.forEach { c ->
        addJsonObject {
            put("name", c.name)
            put("url", c.url)
            put("context", c.context)
            put("path", c.path.toString())
        }
    }
}

private fun textReport(configs: List<KonConfigInfo>): String {
    if (configs.isEmpty()) {
        return "No .kon configurations found in the current directory or ${konConfigDir()}/."
    }
    val nameW = maxOf("NAME".length, configs.maxOf { it.name.length })
    val urlW = maxOf("URL".length, configs.maxOf { it.url.length })
    val header = TextStyles.bold("${"NAME".padEnd(nameW)}  ${"URL".padEnd(urlW)}  CONTEXT")
    return buildString {
        appendLine(header)
        configs.forEach { c ->
            appendLine("${c.name.padEnd(nameW)}  ${c.url.padEnd(urlW)}  ${c.context}")
        }
    }.trimEnd()
}
