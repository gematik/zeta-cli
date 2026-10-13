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
import com.github.ajalt.clikt.core.context
import de.gematik.zeta.cli.config.YamlValueSource
import java.nio.file.Path

class ZetaCommand(private val configPath: Path? = null) : ZetaCliktCommand(name = "zeta") {
    init {
        // Install a YAML-backed value source so options can fall back to a config file.
        // The path was already resolved (and existence-checked when explicit) in Main.kt
        // via `resolveConfigFile`; here we only need to attach it to the Clikt context.
        // This block must stay side-effect-free — Clikt's error handler re-builds the
        // context to format help, so a throw here triggers a re-entrant throw that
        // bypasses the clean error printer.
        context {
            valueSource = configPath?.let(::YamlValueSource)
        }
    }

    override fun help(context: Context) = "Command-line client for TI 2.0 Zero Trust services"
}
