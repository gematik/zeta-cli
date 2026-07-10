package de.gematik.zeta.cli.storage

import de.gematik.zeta.sdk.storage.ResourceScope
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.exists

/**
 * Per-profile SQLite database holding the CLI's SDK state at
 * `$XDG_CONFIG_HOME/telematik/zeta/<profile>.storage.db`. Two tables:
 *
 * - `sdk_state(context, key, value)` — the context-scoped key/value store each SDK client
 *   writes through (see [SqliteSdkStorage]); `context` is a [ResourceScope.storageKey].
 * - `resource_context(context, fqdn, scopes, updated_at)` — the CLI's own registry of the
 *   resource scopes present in the profile. zeta-sdk 1.2.2 dropped its resource index and no
 *   longer lets a consumer enumerate resources, so we keep our own so `zeta status` can list
 *   every cached resource. `scopes` is the sorted space-joined scope list, enough to rebuild a
 *   [ResourceScope]; the `context`/`storageKey` embeds the resource URL and isn't safely parseable.
 *
 * A connection is opened per operation — the CLI runs one short-lived command at a time, so
 * there is nothing to keep open or close. WAL plus a busy timeout keep concurrent access safe.
 */
class ProfileDb(private val path: Path) {
    private val url = "jdbc:sqlite:${path.toAbsolutePath()}"

    val filePath: Path get() = path

    init {
        path.toAbsolutePath().parent?.let { if (!it.exists()) Files.createDirectories(it) }
        withConnection { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS sdk_state (
                        context TEXT NOT NULL,
                        key     TEXT NOT NULL,
                        value   TEXT NOT NULL,
                        PRIMARY KEY (context, key)
                    )
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS resource_context (
                        context    TEXT PRIMARY KEY,
                        fqdn       TEXT NOT NULL,
                        scopes     TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    fun <T> withConnection(block: (Connection) -> T): T =
        DriverManager.getConnection(url).use { c ->
            c.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA synchronous=NORMAL")
                st.execute("PRAGMA busy_timeout=30000")
            }
            block(c)
        }

    /** Register (or refresh) a resource scope so enumeration can find it later. */
    fun recordContext(scope: ResourceScope): Unit = withConnection { c ->
        c.prepareStatement(
            "INSERT OR REPLACE INTO resource_context(context, fqdn, scopes, updated_at) VALUES (?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, scope.storageKey)
            ps.setString(2, scope.fqdn)
            ps.setString(3, scope.scopes.sorted().joinToString(" "))
            ps.setLong(4, System.currentTimeMillis() / 1000)
            ps.executeUpdate()
        }
    }

    /** Every resource scope cached in this profile, oldest FQDN first. */
    fun contexts(): List<ResourceScope> = query("SELECT fqdn, scopes FROM resource_context ORDER BY fqdn")

    /** Every scope recorded for one resource FQDN, most recently touched first. */
    fun contextsForFqdn(fqdn: String): List<ResourceScope> = withConnection { c ->
        c.prepareStatement(
            "SELECT fqdn, scopes FROM resource_context WHERE fqdn = ? ORDER BY updated_at DESC",
        ).use { ps ->
            ps.setString(1, fqdn)
            ps.executeQuery().use(::readScopes)
        }
    }

    /** The most recently touched scope for one resource FQDN, or null if the profile has none. */
    fun latestContextFor(fqdn: String): ResourceScope? = contextsForFqdn(fqdn).firstOrNull()

    private fun query(sql: String): List<ResourceScope> = withConnection { c ->
        c.prepareStatement(sql).use { ps -> ps.executeQuery().use(::readScopes) }
    }

    private fun readScopes(rs: java.sql.ResultSet): List<ResourceScope> = buildList {
        while (rs.next()) {
            val scopes = rs.getString(2).split(' ').filter { it.isNotBlank() }
            add(ResourceScope(rs.getString(1), scopes))
        }
    }

    /** Drop all state for one scope: its `sdk_state` rows and its `resource_context` entry. */
    fun removeContext(scope: ResourceScope): Unit = withConnection { c ->
        c.prepareStatement("DELETE FROM sdk_state WHERE context = ?").use { ps ->
            ps.setString(1, scope.storageKey)
            ps.executeUpdate()
        }
        c.prepareStatement("DELETE FROM resource_context WHERE context = ?").use { ps ->
            ps.setString(1, scope.storageKey)
            ps.executeUpdate()
        }
    }
}
