package de.gematik.zeta.cli

import com.github.ajalt.clikt.core.Context
import de.gematik.zeta.cli.output.OutputFormat
import de.gematik.zeta.cli.output.renderJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class VersionCommand : ZetaCliktCommand(name = "version") {
    override fun help(context: Context) = "Display the version of the Zeta CLI."

    override fun runCommand() {
        // The launcher (when used) sets `zeta.sdk.active` to the SDK it actually loaded;
        // fall back to the compile-time pin when running outside the launcher (zeta-dev,
        // :cli:run).
        val sdkVersion = System.getProperty("zeta.sdk.active")?.takeIf { it.isNotBlank() }
            ?: BuildConfig.ZETA_SDK_VERSION

        when (cliConfig.outputFormat) {
            OutputFormat.JSON -> {
                val payload =
                    buildJsonObject {
                        put("version", JsonPrimitive(BuildConfig.VERSION))
                        put("zeta_sdk_version", JsonPrimitive(sdkVersion))
                    }
                echo(renderJson(payload, colorize = colorize))
            }

            OutputFormat.TEXT, OutputFormat.RAW -> {
                echo("zeta-cli ${BuildConfig.VERSION}")
                echo("zeta-sdk $sdkVersion")
            }
        }
    }
}
