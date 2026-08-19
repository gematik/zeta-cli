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
