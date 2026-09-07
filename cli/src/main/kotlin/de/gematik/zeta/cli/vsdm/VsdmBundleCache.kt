package de.gematik.zeta.cli.vsdm

import de.gematik.zeta.catalog.Environment
import de.gematik.zeta.cli.cache.BlobCodec
import de.gematik.zeta.cli.cache.CacheDb
import de.gematik.zeta.cli.cache.CacheSection
import de.gematik.zeta.cli.cache.PlainBlobCodec
import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.PreparedStatement

private val log = KotlinLogging.logger {}

/**
 * The VSDM bundles inside a [CacheDb]: one row per patient record, keyed by [VsdmCacheKey], holding
 * the service's `ETag` and the body it named. Bodies pass through [codec] on the way in and out, and
 * each row records which codec wrote it — a row this build cannot decode is dropped, not guessed at.
 */
class VsdmBundleCache(
    private val db: CacheDb,
    private val codec: BlobCodec = PlainBlobCodec,
) : VsdmCacheStore, CacheSection {

    override val name: String = "vsdm-bundle"

    init {
        db.migrate { c ->
            c.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS vsdm_bundle (
                        actor_id        TEXT NOT NULL,
                        endpoint_origin TEXT NOT NULL,
                        insurer_id      TEXT NOT NULL,
                        insurant_id     TEXT NOT NULL,
                        profile_version TEXT NOT NULL,
                        content_type    TEXT NOT NULL,
                        env             TEXT NOT NULL,
                        etag            TEXT NOT NULL,
                        body            BLOB NOT NULL,
                        codec           TEXT NOT NULL,
                        popp_token      TEXT,
                        fetched_at      INTEGER NOT NULL,
                        revalidated_at  INTEGER NOT NULL,
                        hits            INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (actor_id, endpoint_origin, insurer_id, insurant_id, profile_version, content_type)
                    )
                    """.trimIndent(),
                )
                st.execute("CREATE INDEX IF NOT EXISTS vsdm_bundle_by_age ON vsdm_bundle(revalidated_at)")
                st.execute("CREATE INDEX IF NOT EXISTS vsdm_bundle_by_insurant ON vsdm_bundle(insurer_id, insurant_id)")
            }
        }
    }

    override fun lookup(key: VsdmCacheKey): CachedBundle? = runCatching {
        db.withConnection { c ->
            c.prepareStatement(
                """
                SELECT etag, body, codec, env, popp_token, fetched_at, revalidated_at FROM vsdm_bundle
                 WHERE actor_id = ? AND endpoint_origin = ? AND insurer_id = ? AND insurant_id = ?
                   AND profile_version = ? AND content_type = ?
                """.trimIndent(),
            ).use { ps ->
                key.bind(ps)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@withConnection null
                    if (rs.getString("codec") != codec.name) {
                        log.debug { "cache row written by codec '${rs.getString("codec")}' — dropping it" }
                        invalidate(key)
                        return@withConnection null
                    }
                    CachedBundle(
                        etag = rs.getString("etag"),
                        body = codec.decode(rs.getBytes("body")),
                        env = Environment.valueOf(rs.getString("env")),
                        poppToken = rs.getString("popp_token"),
                        fetchedAtEpochSec = rs.getLong("fetched_at"),
                        revalidatedAtEpochSec = rs.getLong("revalidated_at"),
                    )
                }
            }
        }
    }.onFailure { log.warn { "VSDM cache lookup failed: ${it.message}" } }.getOrNull()

    override fun store(key: VsdmCacheKey, entry: CachedBundle) {
        runCatching {
            db.withConnection { c ->
                c.prepareStatement(
                    """
                    INSERT OR REPLACE INTO vsdm_bundle
                        (actor_id, endpoint_origin, insurer_id, insurant_id, profile_version, content_type,
                         env, etag, body, codec, popp_token, fetched_at, revalidated_at, hits)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """.trimIndent(),
                ).use { ps ->
                    key.bind(ps)
                    ps.setString(7, entry.env.name)
                    ps.setString(8, entry.etag)
                    ps.setBytes(9, codec.encode(entry.body))
                    ps.setString(10, codec.name)
                    ps.setString(11, entry.poppToken)
                    ps.setLong(12, entry.fetchedAtEpochSec)
                    ps.setLong(13, entry.revalidatedAtEpochSec)
                    ps.executeUpdate()
                }
            }
        }.onFailure { log.warn { "VSDM cache write failed: ${it.message}" } }
    }

    override fun touch(key: VsdmCacheKey, nowEpochSec: Long, poppToken: String?) {
        runCatching {
            db.withConnection { c ->
                c.prepareStatement(
                    """
                    UPDATE vsdm_bundle SET revalidated_at = ?, hits = hits + 1, popp_token = COALESCE(?, popp_token)
                     WHERE actor_id = ? AND endpoint_origin = ? AND insurer_id = ? AND insurant_id = ?
                       AND profile_version = ? AND content_type = ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setLong(1, nowEpochSec)
                    ps.setString(2, poppToken)
                    key.bind(ps, offset = 2)
                    ps.executeUpdate()
                }
            }
        }.onFailure { log.warn { "VSDM cache touch failed: ${it.message}" } }
    }

    override fun invalidate(key: VsdmCacheKey) {
        runCatching {
            db.withConnection { c ->
                c.prepareStatement(
                    """
                    DELETE FROM vsdm_bundle
                     WHERE actor_id = ? AND endpoint_origin = ? AND insurer_id = ? AND insurant_id = ?
                       AND profile_version = ? AND content_type = ?
                    """.trimIndent(),
                ).use { ps ->
                    key.bind(ps)
                    ps.executeUpdate()
                }
            }
        }.onFailure { log.warn { "VSDM cache invalidate failed: ${it.message}" } }
    }

    /** Drop records nobody revalidated within [maxAgeDays], then the least recently revalidated above [maxEntries]. */
    override fun prune(nowEpochSec: Long, maxEntries: Int, maxAgeDays: Int): Int = runCatching {
        db.withConnection { c ->
            var removed = 0
            c.prepareStatement("DELETE FROM vsdm_bundle WHERE revalidated_at < ?").use { ps ->
                ps.setLong(1, nowEpochSec - maxAgeDays * SECONDS_PER_DAY)
                removed += ps.executeUpdate()
            }
            c.prepareStatement(
                """
                DELETE FROM vsdm_bundle WHERE rowid IN (
                    SELECT rowid FROM vsdm_bundle ORDER BY revalidated_at DESC LIMIT -1 OFFSET ?
                )
                """.trimIndent(),
            ).use { ps ->
                ps.setInt(1, maxEntries)
                removed += ps.executeUpdate()
            }
            removed
        }.also { if (it > 0) db.vacuum() }
    }.onFailure { log.warn { "VSDM cache prune failed: ${it.message}" } }.getOrDefault(0)

    /**
     * Delete entries by reading identity, insurer and/or insurant; with none of them, everything.
     * Returns the row count. Deleting the whole cache file does the same thing and is always safe —
     * this is for the targeted case, and `actorId` is what makes a shared file purgeable per tenant.
     */
    fun purge(actorId: String? = null, insurerId: String? = null, insurantId: String? = null): Int = db.withConnection { c ->
        val filters = buildList {
            actorId?.let { add("actor_id = ?" to it) }
            insurerId?.let { add("insurer_id = ?" to it) }
            insurantId?.let { add("insurant_id = ?" to it) }
        }
        val where = if (filters.isEmpty()) "" else " WHERE " + filters.joinToString(" AND ") { it.first }
        c.prepareStatement("DELETE FROM vsdm_bundle$where").use { ps ->
            filters.forEachIndexed { i, (_, value) -> ps.setString(i + 1, value) }
            ps.executeUpdate()
        }
    }.also { if (it > 0) db.vacuum() }

    /** Drop the stored PoPP tokens, keeping the bundles they were read with. */
    fun clearPoppTokens(): Int = db.withConnection { c ->
        c.createStatement().use {
            it.executeUpdate("UPDATE vsdm_bundle SET popp_token = NULL WHERE popp_token IS NOT NULL")
        }
    }

    override fun entryCount(): Long = db.withConnection { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM vsdm_bundle").use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    /** How many SMC-B identities have entries here — the first question about a shared cache file. */
    fun actorCount(): Long = db.withConnection { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(DISTINCT actor_id) FROM vsdm_bundle").use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    /** Epoch seconds of the least recently revalidated entry, or null when the section is empty. */
    fun oldestRevalidationEpochSec(): Long? = db.withConnection { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT MIN(revalidated_at) FROM vsdm_bundle").use { rs ->
                rs.next()
                rs.getLong(1).takeIf { !rs.wasNull() }
            }
        }
    }

    private companion object {
        const val SECONDS_PER_DAY = 86_400L
    }
}

private fun VsdmCacheKey.bind(ps: PreparedStatement, offset: Int = 0) {
    ps.setString(offset + 1, actorId)
    ps.setString(offset + 2, endpointOrigin)
    ps.setString(offset + 3, insurerId)
    ps.setString(offset + 4, insurantId)
    ps.setString(offset + 5, profileVersion)
    ps.setString(offset + 6, contentType)
}
