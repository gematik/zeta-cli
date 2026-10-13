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
package de.gematik.zeta.catalog

import kotlinx.serialization.Serializable

/**
 * Persistence seam for the service-discovery cache: one string slot per [Environment]. Kept
 * deliberately tiny and storage-agnostic so the `catalog` package carries no dependency on the
 * CLI's storage layer — the CLI supplies an implementation (e.g. over the profile database).
 * Implementations should be best-effort: a read/write failure is a cache miss, never an error.
 */
interface CatalogStore {
    fun read(env: Environment): String?
    fun write(env: Environment, value: String)
}

/** A cached catalog body plus the metadata needed to judge its freshness; serialized into a [CatalogStore]. */
@Serializable
data class CachedCatalog(
    val fetchedAtEpochSec: Long,
    val maxAgeSec: Long,
    val etag: String? = null,
    /** The raw `catalog.json` body, re-parsed on a hit so nothing is lost in a serialization round-trip. */
    val body: String,
) {
    fun ageSec(nowEpochSec: Long): Long = nowEpochSec - fetchedAtEpochSec
    fun isFresh(nowEpochSec: Long): Boolean = ageSec(nowEpochSec) < maxAgeSec
}
