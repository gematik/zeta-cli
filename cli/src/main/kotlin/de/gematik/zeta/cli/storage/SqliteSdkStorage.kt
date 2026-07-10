package de.gematik.zeta.cli.storage

import de.gematik.zeta.sdk.storage.SdkStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [SdkStorage] backed by [ProfileDb]'s `sdk_state` table, scoped to one `context` (a
 * [de.gematik.zeta.sdk.storage.ResourceScope] storage key).
 *
 * Each SDK client built for a resource+scope gets its own instance, so per-resource state never
 * collides in the shared profile DB. This isolation is load-bearing under zeta-sdk 1.2.2: for a
 * `StorageConfig.Custom` storage the SDK does *not* namespace access tokens by resource (they land
 * on a fixed key), so without the `context` column two resources in one profile would overwrite
 * each other's tokens.
 */
class SqliteSdkStorage(private val context: String, private val db: ProfileDb) : SdkStorage {

    override suspend fun put(key: String, value: String): Unit = withContext(Dispatchers.IO) {
        db.withConnection { c ->
            c.prepareStatement(
                "INSERT OR REPLACE INTO sdk_state(context, key, value) VALUES (?, ?, ?)",
            ).use { ps ->
                ps.setString(1, context)
                ps.setString(2, key)
                ps.setString(3, value)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        db.withConnection { c ->
            c.prepareStatement("SELECT value FROM sdk_state WHERE context = ? AND key = ?").use { ps ->
                ps.setString(1, context)
                ps.setString(2, key)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
    }

    override suspend fun remove(key: String): Unit = withContext(Dispatchers.IO) {
        db.withConnection { c ->
            c.prepareStatement("DELETE FROM sdk_state WHERE context = ? AND key = ?").use { ps ->
                ps.setString(1, context)
                ps.setString(2, key)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        db.withConnection { c ->
            c.prepareStatement("DELETE FROM sdk_state WHERE context = ?").use { ps ->
                ps.setString(1, context)
                ps.executeUpdate()
            }
        }
    }
}
