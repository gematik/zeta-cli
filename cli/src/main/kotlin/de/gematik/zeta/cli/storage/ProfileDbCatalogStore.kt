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

import de.gematik.zeta.catalog.CatalogStore
import de.gematik.zeta.catalog.Environment

/**
 * [CatalogStore] backed by a profile's [ProfileDb]: the service-discovery cache lives in the same
 * `<profile>.storage.db` as the rest of the CLI's state, under a reserved `sdk_state` context that
 * can't collide with a resource scope. Best-effort — a read or write failure degrades to a cache
 * miss rather than failing the command.
 */
class ProfileDbCatalogStore(private val db: ProfileDb) : CatalogStore {
    override fun read(env: Environment): String? =
        runCatching { db.getState(CONTEXT, env.name.lowercase()) }.getOrNull()

    override fun write(env: Environment, value: String) {
        runCatching { db.putState(CONTEXT, env.name.lowercase(), value) }
    }

    private companion object {
        const val CONTEXT = "service-discovery"
    }
}
