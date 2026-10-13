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

            OutputFormat.TEXT, OutputFormat.RAW -> {
                echo("zeta-cli ${BuildConfig.VERSION}")
                echo("zeta-sdk ${BuildConfig.ZETA_SDK_VERSION}")
            }
        }
    }
}
