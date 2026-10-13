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
package de.gematik.zeta.stress.storage

import de.gematik.zeta.sdk.storage.SdkStorage
import de.gematik.zeta.stress.db.Db
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [SdkStorage] backed by the shared SQLite `sdk_state` table, scoped to one [clientRef].
 *
 * Every virtual client gets its own instance with a distinct namespace. This isolation is not
 * cosmetic: the SDK's domain stores do non-atomic read-modify-write on shared index/map keys
 * (`hash_index`, `client_registration_by_auth_server`, the resource index), so two clients sharing
 * a namespace would clobber each other's indexes. One namespace per client removes all
 * cross-client contention, and each client is driven by a single logical actor, so no
 * intra-namespace locking is needed beyond [Db]'s own connection lock.
 */
class SqliteSdkStorage(private val clientRef: String, private val db: Db) : SdkStorage {

    override suspend fun put(key: String, value: String): Unit = withContext(Dispatchers.IO) {
        db.withConnection { c ->
            c.prepareStatement(
                "INSERT OR REPLACE INTO sdk_state(client_ref, key, value) VALUES (?, ?, ?)",
            ).use { ps ->
                ps.setString(1, clientRef)
                ps.setString(2, key)
                ps.setString(3, value)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        db.withConnection { c ->
            c.prepareStatement("SELECT value FROM sdk_state WHERE client_ref = ? AND key = ?").use { ps ->
                ps.setString(1, clientRef)
                ps.setString(2, key)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
    }

    override suspend fun remove(key: String): Unit = withContext(Dispatchers.IO) {
        db.withConnection { c ->
            c.prepareStatement("DELETE FROM sdk_state WHERE client_ref = ? AND key = ?").use { ps ->
                ps.setString(1, clientRef)
                ps.setString(2, key)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        db.withConnection { c ->
            c.prepareStatement("DELETE FROM sdk_state WHERE client_ref = ?").use { ps ->
                ps.setString(1, clientRef)
                ps.executeUpdate()
            }
        }
    }
}
