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
package de.gematik.zeta.cli.state

import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * The profile-wide status as a JSON object: `{ profile, path, resources: [ <entry>… ] }`, one
 * `resources` element per cached resource via [renderEntryJson]. Shared by `zeta status -o json`
 * and the `GET /api/zeta/status` endpoint so the two never drift. `reveal` exposes normally-redacted
 * secrets, matching `zeta status --reveal`.
 */
internal fun profileStatusJson(
    profile: String,
    path: Path,
    entries: List<Entry>,
    reveal: Boolean = false,
): JsonObject = buildJsonObject {
    put("profile", JsonPrimitive(profile))
    put("path", JsonPrimitive(path.toString()))
    put("resources", buildJsonArray { entries.forEach { add(renderEntryJson(it, reveal)) } })
}
