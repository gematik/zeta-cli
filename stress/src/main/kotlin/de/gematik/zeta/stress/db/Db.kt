package de.gematik.zeta.stress.db

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Single-file SQLite store shared by the whole harness: the imported SMC-B card corpus, the
 * registered virtual-client roster, and every client's SDK key/value state.
 *
 * One JDBC connection, guarded by a [ReentrantLock]. SQLite has a single writer anyway, and at
 * the target load (≈83 ops/s, sub-millisecond each) serial access is not the bottleneck — the
 * network round-trips to ZETA Guard are. WAL mode + a generous `busy_timeout` keep the rare
 * lock contention from surfacing as `SQLITE_BUSY`. Callers bridge from `suspend` code via
 * `Dispatchers.IO`; the lock keeps that safe regardless of dispatcher threading.
 */
class Db(path: Path) : AutoCloseable {
    private val conn: Connection =
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").apply {
            createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA synchronous=NORMAL")
                st.execute("PRAGMA busy_timeout=30000")
                st.execute("PRAGMA foreign_keys=ON")
            }
            autoCommit = true
        }

    private val lock = ReentrantLock()

    init {
        migrate()
    }

    fun <T> withConnection(block: (Connection) -> T): T = lock.withLock { block(conn) }

    /** Run [block] in a single transaction, committing on success and rolling back on failure. */
    fun <T> transaction(block: (Connection) -> T): T = lock.withLock {
        conn.autoCommit = false
        try {
            val result = block(conn)
            conn.commit()
            result
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    private fun migrate() = withConnection { c ->
        c.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS card (
                    card_id   TEXT PRIMARY KEY,
                    cert      BLOB NOT NULL,
                    priv_key  BLOB NOT NULL
                )
                """.trimIndent(),
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS client (
                    client_ref TEXT PRIMARY KEY,
                    card_id    TEXT NOT NULL,
                    resource   TEXT NOT NULL,
                    scopes     TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    status     TEXT NOT NULL
                )
                """.trimIndent(),
            )
            st.execute("CREATE INDEX IF NOT EXISTS client_by_card ON client(card_id)")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS sdk_state (
                    client_ref TEXT NOT NULL,
                    key        TEXT NOT NULL,
                    value      TEXT NOT NULL,
                    PRIMARY KEY (client_ref, key)
                )
                """.trimIndent(),
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS result (
                    ts         INTEGER NOT NULL,
                    client_ref TEXT NOT NULL,
                    op         TEXT NOT NULL,
                    latency_ms INTEGER NOT NULL,
                    outcome    TEXT NOT NULL,
                    error      TEXT
                )
                """.trimIndent(),
            )
        }
    }

    override fun close() = lock.withLock { conn.close() }
}
