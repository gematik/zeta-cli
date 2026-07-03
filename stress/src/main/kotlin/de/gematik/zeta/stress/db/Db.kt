package de.gematik.zeta.stress.db

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.ArrayBlockingQueue

/**
 * Single-file SQLite store shared by the whole harness: the imported SMC-B card corpus, the
 * registered virtual-client roster, and every client's SDK key/value state.
 *
 * Backed by a small **pool of WAL connections** rather than one connection behind a global lock.
 * A single lock serialised every storage op — reads included — across all virtual clients, which
 * showed up under load as flat throughput with climbing latency (a serialised-bottleneck
 * signature) long before ZETA Guard itself bent. With a pool, reads run concurrently and writes
 * serialise only at SQLite's own write lock; `busy_timeout` makes the rare writer collision wait
 * instead of surfacing as `SQLITE_BUSY`. Each virtual client already has an isolated key
 * namespace, so no cross-connection coordination is needed above the SQLite level.
 */
class Db(path: Path, poolSize: Int = 32) : AutoCloseable {
    private val url = "jdbc:sqlite:${path.toAbsolutePath()}"
    private val pool = ArrayBlockingQueue<Connection>(poolSize)

    init {
        repeat(poolSize) { pool.add(open()) }
        migrate()
    }

    private fun open(): Connection =
        DriverManager.getConnection(url).apply {
            createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA synchronous=NORMAL")
                st.execute("PRAGMA busy_timeout=30000")
                st.execute("PRAGMA foreign_keys=ON")
            }
            autoCommit = true
        }

    fun <T> withConnection(block: (Connection) -> T): T {
        val c = pool.take()
        try {
            return block(c)
        } finally {
            pool.put(c)
        }
    }

    /** Run [block] in a single transaction, committing on success and rolling back on failure. */
    fun <T> transaction(block: (Connection) -> T): T = withConnection { c ->
        c.autoCommit = false
        try {
            val result = block(c)
            c.commit()
            result
        } catch (e: Throwable) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
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

    override fun close() {
        val drained = mutableListOf<Connection>()
        pool.drainTo(drained)
        drained.forEach { runCatching { it.close() } }
    }
}
