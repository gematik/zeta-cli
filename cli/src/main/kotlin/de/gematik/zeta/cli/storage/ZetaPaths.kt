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
package de.gematik.zeta.cli.storage

import de.gematik.zeta.cli.connector.xdgConfigHome
import java.nio.file.Path

/**
 * Paths for zeta CLI runtime state — token caches, registration data, anything the SDK
 * persists via [de.gematik.zeta.sdk.storage.SdkStorage].
 *
 * Layout matches the kon-files directory: `$XDG_CONFIG_HOME/telematik/<app>/`. macOS
 * uses `~/.config` like Linux (deliberately, not Apple's `~/Library/Application Support`)
 * so identical files work on both platforms — see [xdgConfigHome].
 */
private const val ZETA_SUBDIR = "telematik/zeta"

/** `$XDG_CONFIG_HOME/telematik/zeta/`. Created on demand by storage writers, not here. */
fun zetaConfigDir(): Path = xdgConfigHome().resolve(ZETA_SUBDIR)

/**
 * Path to the SQLite state database for a named profile.
 *
 * `<zetaConfigDir>/<profile>.storage.db`. The compound `.storage.db` suffix leaves room for
 * other files (configuration, exports, transient state) to coexist in the same directory
 * without name collisions. Multiple profiles let users separate state per Connector / per
 * environment without colliding; pair with the `.kon` profile name for a one-to-one mapping.
 */
fun zetaProfilePath(profile: String): Path = zetaConfigDir().resolve("$profile.storage.db")
