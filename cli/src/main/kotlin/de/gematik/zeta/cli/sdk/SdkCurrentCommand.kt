package de.gematik.zeta.cli.sdk

import com.github.ajalt.clikt.core.Context
import de.gematik.zeta.cli.ZetaCliktCommand
import de.gematik.zeta.cli.output.OutputFormat
import de.gematik.zeta.cli.output.renderJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SdkCurrentCommand : ZetaCliktCommand(name = "current") {
    override fun help(context: Context) =
        "Print the active zeta-sdk version and how it was selected."

    override fun runCommand() {
        val active = activeSdk()
        val source = activeSdkSource()

        when (cliConfig.outputFormat) {
            OutputFormat.JSON -> echo(renderJson(jsonReport(active, source), colorize = colorize))
            OutputFormat.TEXT, OutputFormat.RAW -> echo(textReport(active, source))
        }
    }
}

private fun jsonReport(active: String?, source: String?): JsonObject = buildJsonObject {
    put("active", active?.let(::JsonPrimitive) ?: JsonNull)
    put("source", source?.let(::JsonPrimitive) ?: JsonNull)
}

private fun textReport(active: String?, source: String?): String =
    if (active == null) {
        "No active SDK detected (cli is not running under the launcher)."
    } else {
        "$active  (source: ${source ?: "unknown"})"
    }
