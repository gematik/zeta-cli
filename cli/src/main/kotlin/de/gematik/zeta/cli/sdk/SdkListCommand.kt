package de.gematik.zeta.cli.sdk

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.mordant.rendering.TextStyles
import de.gematik.zeta.cli.ZetaCliktCommand
import de.gematik.zeta.cli.output.OutputFormat
import de.gematik.zeta.cli.output.renderJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

private const val ACTIVE_MARKER = "*"
private const val INACTIVE_MARKER = " "

class SdkListCommand : ZetaCliktCommand(name = "list") {
    override fun help(context: Context) =
        "List zeta-sdk versions bundled in the running zeta install. Marks the active " +
            "version (driven by --sdk / ZETA_SDK / `zeta sdk use` / bundled default)."

    override fun runCommand() {
        val available = availableSdks()
        val active = activeSdk()
        val default = defaultSdk()

        when (cliConfig.outputFormat) {
            OutputFormat.JSON -> echo(renderJson(sdkListJson(available, active, default), colorize = colorize))
            OutputFormat.TEXT, OutputFormat.RAW -> echo(sdkListText(available, active, default))
        }
    }
}

internal fun sdkListJson(available: List<String>, active: String?, default: String?): JsonArray =
    buildJsonArray {
        available.forEach { v ->
            addJsonObject {
                put("version", v)
                put("active", v == active)
                put("default", v == default)
            }
        }
    }

internal fun sdkListText(available: List<String>, active: String?, default: String?): String {
    if (available.isEmpty()) {
        return "No bundled SDK versions detected. Are you running the launcher distribution?"
    }
    val verW = maxOf("VERSION".length, available.maxOf { it.length })
    val header = TextStyles.bold("  ${"VERSION".padEnd(verW)}  TAGS")
    return buildString {
        appendLine(header)
        available.forEach { v ->
            val marker = if (v == active) ACTIVE_MARKER else INACTIVE_MARKER
            val tags = listOfNotNull(
                "active".takeIf { v == active },
                "default".takeIf { v == default },
            ).joinToString(", ")
            appendLine("$marker ${v.padEnd(verW)}  $tags")
        }
    }.trimEnd()
}
