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
package de.gematik.zeta.cli.config

import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Resolve the single `zeta.yaml` file to load for this invocation.
 *
 * Precedence:
 *   1. [override] — the value of `-f/--file` or `ZETA_CONFIG`, pre-detected by `Main.kt`
 *      before Clikt parses. When set, the file MUST exist — a missing path is an explicit
 *      bug rather than a silent fallback, so we surface it.
 *   2. Auto-discovery via [discoverZetaConfigFile] — project-local `./zeta.yaml`, else
 *      `$XDG_CONFIG_HOME/telematik/zeta/zeta.yaml`. Either or both may legitimately be
 *      absent; in that case the function returns `null` and the CLI runs without a file.
 *
 * Throws [ConfigFileMissingException] when [override] is non-null and the file is absent.
 */
fun resolveConfigFile(override: Path?): Path? {
    if (override != null) {
        if (!override.exists()) throw ConfigFileMissingException(override)
        return override
    }
    return discoverZetaConfigFile()
}

class ConfigFileMissingException(val path: Path) :
    RuntimeException("config file not found: $path")
