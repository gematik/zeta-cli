package de.gematik.zeta.cli.cache

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.ArrayBlockingQueue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val log = KotlinLogging.logger {}

/** Identifies a file as ours before any table is read — and, once encrypted, without the key. */
@Serializable
data class CacheDbMeta(
    val schemaVersion: Int,
    val cipher: String,
    val keyId: String? = null,
    val createdAt: Long,
)

/**
 * The CLI's optional on-disk cache: one SQLite file that any number of [CacheSection]s share, each
 * owning its own tables. `--cache-db` names it; without that flag nothing is cached at all.
 *
 * It is a file of its own, never the profile database. The profile DB must stay readable by the
 * plain SQLite driver, cached payloads can carry personal data that has to be separately
 * deletable, and only a separate file can later be swapped for an encrypted one.
 *
 * Backed by a small WAL connection pool. That is not about throughput — the daemon serialises its
 * reads anyway — but about that encryption step: `PRAGMA key` derives a key on every physical
 * connection, so opening one per operation would make the switch expensive. For the same reason
 * this class is the only place that opens the file.
 *
 * A cache is disposable — nothing lives only here — so a file we cannot read is discarded and
 * recreated rather than migrated. A file without our marker is never touched.
 */
class CacheDb(
    private val path: Path,
    @Suppress("unused") private val key: CharArray? = null,
    poolSize: Int = 4,
) : AutoCloseable {

    private val url = "jdbc:sqlite:${path.toAbsolutePath()}"
    private val pool = ArrayBlockingQueue<Connection>(poolSize)
    private val sidecar: Path = path.resolveSibling(path.fileName.toString() + ".meta.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    init {
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        if (!claimUsable()) recreate()
        val fresh = Files.notExists(path)
        repeat(poolSize) { pool.add(open()) }
        if (fresh) stamp()
        writeMeta()
        restrictPermissions()
        log.warn {
            "cache enabled: $path — cached payloads are stored unencrypted. Delete the file to discard them."
        }
    }

    private fun open(): Connection =
        DriverManager.getConnection(url).apply {
            createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA synchronous=NORMAL")
                st.execute("PRAGMA busy_timeout=30000")
                st.execute("PRAGMA secure_delete=ON")
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

    /** Create a section's tables. Idempotent DDL only — there are no versioned migrations. */
    fun migrate(block: (Connection) -> Unit) = withConnection(block)

    /** Reclaim space freed by a delete; `auto_vacuum=INCREMENTAL` was set when the file was made. */
    fun vacuum() = withConnection { c -> c.createStatement().use { it.execute("PRAGMA incremental_vacuum") } }

    /** Size on disk, WAL included — with `journal_mode=WAL` most of a young cache lives there. */
    fun fileBytes(): Long = listOf(path, Path.of("$path-wal"))
        .sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }

    override fun toString(): String = path.toString()

    /**
     * True when the file is absent (nothing to claim) or carries our `application_id`. A file
     * belonging to someone else raises rather than being silently discarded.
     */
    private fun claimUsable(): Boolean {
        if (Files.notExists(path)) return true
        val marker = runCatching {
            DriverManager.getConnection(url).use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("PRAGMA application_id").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                }
            }
        }.getOrNull()
        if (marker == APPLICATION_ID) return true
        if (marker != null && marker != 0) {
            error("$path is a SQLite database belonging to another application (application_id=$marker)")
        }
        val ours = runCatching { json.decodeFromString<CacheDbMeta>(Files.readString(sidecar)) }.isSuccess
        if (!ours) error("$path exists but is not a readable zeta cache; move it aside or pick another --cache-db")
        return false
    }

    private fun recreate() {
        log.warn { "cache at $path is unreadable — discarding and recreating it" }
        listOf(path, Path.of("$path-wal"), Path.of("$path-shm"), sidecar).forEach {
            runCatching { Files.deleteIfExists(it) }
        }
    }

    /** Header fields that can only be chosen while the database is still empty. */
    private fun stamp() = withConnection { c ->
        c.createStatement().use { st ->
            st.execute("PRAGMA auto_vacuum=INCREMENTAL")
            st.execute("PRAGMA application_id=$APPLICATION_ID")
            st.execute("PRAGMA user_version=$SCHEMA_VERSION")
        }
    }

    /**
     * The plaintext sidecar answering "is this ours, and which key wrote it?". It has to live
     * outside the database: once the file is encrypted, the key id is needed *to open it*, and
     * `application_id` is no longer readable without the key either. Written from the start with
     * `cipher: "none"` so that switch only changes values, never structure.
     */
    private fun writeMeta() {
        val now = System.currentTimeMillis() / 1000
        withConnection { c ->
            c.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS cache_meta (key TEXT PRIMARY KEY, value TEXT)") }
            c.prepareStatement("INSERT OR IGNORE INTO cache_meta(key, value) VALUES (?, ?)").use { ps ->
                listOf(
                    "schema_version" to SCHEMA_VERSION.toString(),
                    "created_at" to now.toString(),
                    "cipher" to CIPHER_NONE,
                ).forEach { (k, v) ->
                    ps.setString(1, k)
                    ps.setString(2, v)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
        val meta = CacheDbMeta(SCHEMA_VERSION, CIPHER_NONE, keyId = null, createdAt = now)
        runCatching { Files.writeString(sidecar, json.encodeToString(CacheDbMeta.serializer(), meta)) }
            .onFailure { log.warn { "could not write ${sidecar.fileName}: ${it.message}" } }
    }

    private fun restrictPermissions() {
        listOf(path, sidecar).forEach {
            runCatching { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rw-------")) }
        }
    }

    override fun close() {
        val drained = mutableListOf<Connection>()
        pool.drainTo(drained)
        drained.forEach { runCatching { it.close() } }
    }

    private companion object {
        // "ZETA" as a big-endian ASCII quad, the conventional way to pick an application_id.
        const val APPLICATION_ID = 0x5A455441
        const val SCHEMA_VERSION = 1
        const val CIPHER_NONE = "none"
    }
}
