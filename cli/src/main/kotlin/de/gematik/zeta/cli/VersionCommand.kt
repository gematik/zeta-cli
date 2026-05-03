package de.gematik.zeta.cli

import com.github.ajalt.clikt.core.Context
import de.gematik.zeta.cli.output.OutputFormat
import de.gematik.zeta.cli.output.renderJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class VersionCommand : ZetaCliktCommand(name = "version") {
    override fun help(context: Context) = "Display the version of the Zeta CLI."

    override fun runCommand() {
        when (cliConfig.outputFormat) {
            OutputFormat.JSON -> {
                val payload =
                    buildJsonObject {
                        put("version", JsonPrimitive(BuildConfig.VERSION))
                        put("zeta_sdk_version", JsonPrimitive(BuildConfig.ZETA_SDK_VERSION))
                    }
                echo(renderJson(payload, colorize = colorize))
            }

            OutputFormat.TEXT -> {
                echo("zeta-cli ${BuildConfig.VERSION}")
                echo("zeta-sdk ${BuildConfig.ZETA_SDK_VERSION}")
            }
        }
    }
}
