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
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import de.gematik.zeta.cli.ZetaCliktCommand

/**
 * `zeta connector use <name>` — record [activeConnectorFile] so subsequent commands without
 * `-c/--connector-config` or `ZETA_CONNECTOR_CONFIG` pick up the chosen `.kon`. Validates
 * the name resolves to a real file before writing, so a typo is rejected here rather than
 * deferred to the next command.
 */
class ConnectorUseCommand : ZetaCliktCommand(name = "use") {
    private val name: String by argument(
        name = "NAME",
        help = "Name of a .kon configuration discoverable by `zeta connector configs`.",
    )

    override fun help(context: Context) =
        "Set the active connector configuration. Stored at " +
            "\$XDG_CONFIG_HOME/telematik/connectors/active and consulted when " +
            "-c/--connector-config and ZETA_CONNECTOR_CONFIG are unset."

    override fun runCommand() {
        try {
            resolveKonFile(name)
        } catch (e: KonFileNotFoundException) {
            throw UsageError(e.message ?: "kon file not found")
        }
        writeActiveConnector(name)
        echo("active connector set to \"$name\"", err = true)
    }
}
