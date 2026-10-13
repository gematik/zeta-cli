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

/**
 * Pre-Clikt argv scanner. Used for options that need to be resolved *before* Clikt parses
 * — currently `-f/--file` (selects the YAML value source path) and indirectly the bare
 * `--trace` presence check. Both must wire infrastructure (config-file value source /
 * tracer) into the Clikt context before any subcommand dispatches.
 *
 * Recognises `-n VALUE`, `-n=VALUE`, `--name VALUE`, `--name=VALUE` for each given option
 * spelling. First match wins. POSIX bundling (`-fX`) is intentionally not supported — no
 * pre-parse option is bundleable, and we don't want to ambiguously chew letters out of
 * forms like `-vvv`.
 */
internal fun sniffOptValue(args: Array<String>, vararg names: String): String? {
    for (i in args.indices) {
        val a = args[i]
        for (name in names) {
            if (a == name && i + 1 < args.size) return args[i + 1]
            val eq = "$name="
            if (a.startsWith(eq)) return a.substring(eq.length)
        }
    }
    return null
}
