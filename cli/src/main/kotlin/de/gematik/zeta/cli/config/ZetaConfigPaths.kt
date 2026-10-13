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

import de.gematik.zeta.cli.storage.zetaConfigDir
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists

/** Filename used for both the project-local and user-global config files. */
const val ZETA_CONFIG_FILENAME = "zeta.yaml"

/**
 * `$XDG_CONFIG_HOME/telematik/zeta/zeta.yaml` on every OS — same XDG semantics as the
 * `.kon` lookup in [de.gematik.zeta.cli.connector.DotkonPaths]. macOS deliberately follows
 * the Linux convention (`~/.config`) over `~/Library/Application Support` so the same file
 * works on both platforms; override with `XDG_CONFIG_HOME` if you really want elsewhere.
 */
fun zetaXdgConfigFile(): Path = zetaConfigDir().resolve(ZETA_CONFIG_FILENAME)

/** `./zeta.yaml` if it exists in the current working directory, otherwise `null`. */
fun zetaCwdConfigFile(): Path? = Path(ZETA_CONFIG_FILENAME).takeIf { it.exists() }

/**
 * The single `zeta.yaml` to use, or `null` if none exists. Project-local (`./zeta.yaml`)
 * wins outright when present — the user-global XDG file is ignored, never merged. This
 * keeps the project file self-contained: dropping one in a directory fully describes the
 * intended config, with no surprise inheritance from `$XDG_CONFIG_HOME` (e.g. a stray
 * `auth-connector-telematik-id` there leaking into a project that wants only `--auth-p12-*`).
 * Falls back to the XDG file when cwd has none.
 */
fun discoverZetaConfigFile(): Path? =
    zetaCwdConfigFile() ?: zetaXdgConfigFile().takeIf { it.exists() }
