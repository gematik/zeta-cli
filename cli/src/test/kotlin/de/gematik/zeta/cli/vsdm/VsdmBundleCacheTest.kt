package de.gematik.zeta.cli.vsdm

import de.gematik.zeta.catalog.Environment
import de.gematik.zeta.cli.cache.BlobCodec
import de.gematik.zeta.cli.cache.CacheDb
import de.gematik.zeta.cli.cache.PlainBlobCodec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class VsdmBundleCacheTest {

    /** The file plus its VSDM section; `use` closes the file, the section holds no resources. */
    private fun openCache(path: Path, codec: BlobCodec = PlainBlobCodec): OpenCache {
        val db = CacheDb(path)
        return OpenCache(db, VsdmBundleCache(db, codec))
    }

    private class OpenCache(val db: CacheDb, val bundles: VsdmBundleCache) : AutoCloseable {
        operator fun component1() = db
        operator fun component2() = bundles
        override fun close() = db.close()
    }


    private fun key(
        insurant: String = "X110411675",
        actor: String = "5-2-KHAUS-1",
        contentType: String = "application/fhir+json",
    ) = VsdmCacheKey(
        actorId = actor,
        endpointOrigin = "https://vsdm-dev.tk.de",
        insurerId = "101575519",
        insurantId = insurant,
        profileVersion = "1.1",
        contentType = contentType,
    )

    private fun bundle(
        etag: String = "\"v1\"",
        body: String = """{"resourceType":"Bundle"}""",
        token: String? = "popp-1",
        at: Long = 1_000,
    ) = CachedBundle(etag, body.toByteArray(), Environment.DEV, token, at, at)

    @Test
    fun `round trip returns the stored bundle`(@TempDir dir: Path) {
        openCache(dir.resolve("c.db")).use { (_, db) ->
            assertNull(db.lookup(key()))
            db.store(key(), bundle())
            val got = db.lookup(key()) ?: fail("expected a cache entry")
            assertEquals("\"v1\"", got.etag)
            assertArrayEquals("""{"resourceType":"Bundle"}""".toByteArray(), got.body)
            assertEquals("popp-1", got.poppToken)
        }
    }

    @Test
    fun `a new version replaces the old one for the same record`(@TempDir dir: Path) {
        openCache(dir.resolve("c.db")).use { (_, db) ->
            db.store(key(), bundle(etag = "\"v1\"", body = "old"))
            db.store(key(), bundle(etag = "\"v2\"", body = "new"))
            val got = db.lookup(key()) ?: fail("expected a cache entry")
            assertEquals("\"v2\"", got.etag)
            assertArrayEquals("new".toByteArray(), got.body)
            assertEquals(1, db.entryCount())
        }
    }

    @Test
    fun `content type and patient separate entries`(@TempDir dir: Path) {
        openCache(dir.resolve("c.db")).use { (_, db) ->
            db.store(key(), bundle(body = "json"))
            db.store(key(contentType = "application/fhir+xml"), bundle(body = "xml"))
            db.store(key(insurant = "X999"), bundle(body = "other"))

            assertArrayEquals("json".toByteArray(), db.lookup(key())!!.body)
            assertArrayEquals("xml".toByteArray(), db.lookup(key(contentType = "application/fhir+xml"))!!.body)
            assertArrayEquals("other".toByteArray(), db.lookup(key(insurant = "X999"))!!.body)
            assertNull(db.lookup(key(contentType = "application/cbor")))
        }
    }

    @Test
    fun `touch refreshes the clock and the popp token, keeping the body`(@TempDir dir: Path) {
        openCache(dir.resolve("c.db")).use { (_, db) ->
            db.store(key(), bundle(at = 1_000, token = "popp-1"))
            db.touch(key(), nowEpochSec = 2_000, poppToken = "popp-2")

            val got = db.lookup(key()) ?: fail("expected a cache entry")
            assertEquals(1_000, got.fetchedAtEpochSec)
            assertEquals(2_000, got.revalidatedAtEpochSec)
            assertEquals("popp-2", got.poppToken)
            assertArrayEquals("""{"resourceType":"Bundle"}""".toByteArray(), got.body)
        }
    }

    @Test
    fun `invalidate removes the row`(@TempDir dir: Path) {
        openCache(dir.resolve("c.db")).use { (_, db) ->
            db.store(key(), bundle())
            db.invalidate(key())
            assertNull(db.lookup(key()))
        }
    }

    @Test
    fun `prune drops rows past the retention age`(@TempDir dir: Path) {
        openCache(dir.resolve("c.db")).use { (_, db) ->
            val now = 1_000_000L
            db.store(key(insurant = "old"), bundle(at = now - 200 * 86_400))
            db.store(key(insurant = "fresh"), bundle(at = now - 86_400))

            assertEquals(1, db.prune(now, maxEntries = 100, maxAgeDays = 180))
            assertNull(db.lookup(key(insurant = "old")))
            assertNotNull(db.lookup(key(insurant = "fresh")))
        }
    }

    @Test
    fun `prune evicts the least recently revalidated rows above the bound`(@TempDir dir: Path) {
        openCache(dir.resolve("c.db")).use { (_, db) ->
            val now = 1_000_000L
            (1..5).forEach { db.store(key(insurant = "p$it"), bundle(at = now - it * 10)) }

            assertEquals(2, db.prune(now, maxEntries = 3, maxAgeDays = 180))
            assertNotNull(db.lookup(key(insurant = "p1")))
            assertNotNull(db.lookup(key(insurant = "p3")))
            assertNull(db.lookup(key(insurant = "p4")), "oldest revalidation goes first")
            assertNull(db.lookup(key(insurant = "p5")))
        }
    }

    @Test
    fun `a row written by an unknown codec is dropped rather than guessed at`(@TempDir dir: Path) {
        val file = dir.resolve("c.db")
        openCache(file, NamedCodec("v2")).use { (_, db) -> db.store(key(), bundle()) }
        openCache(file, NamedCodec("v3")).use { (_, db) ->
            assertNull(db.lookup(key()))
            assertEquals(0, db.entryCount())
        }
    }

    @Test
    fun `an unreadable cache file is discarded and recreated, sidecar included`(@TempDir dir: Path) {
        val file = dir.resolve("c.db")
        openCache(file).use { (_, db) -> db.store(key(), bundle()) }
        file.writeText("not a database at all")

        openCache(file).use { (_, db) ->
            assertNull(db.lookup(key()))
            db.store(key(), bundle())
            assertNotNull(db.lookup(key()))
        }
        assertTrue(Files.exists(dir.resolve("c.db.meta.json")))
    }

    @Test
    fun `a foreign file is refused, never deleted`(@TempDir dir: Path) {
        val file = dir.resolve("someone-elses.db")
        file.writeText("definitely not ours")

        assertThrows(IllegalStateException::class.java) { CacheDb(file) }
        assertTrue(Files.exists(file))
        assertEquals("definitely not ours", Files.readString(file))
    }

    @Test
    fun `the sidecar records schema and cipher from the first open`(@TempDir dir: Path) {
        openCache(dir.resolve("c.db")).use { }
        val meta = Files.readString(dir.resolve("c.db.meta.json"))
        assertTrue(meta.contains("\"schemaVersion\": 2"), meta)
        assertTrue(meta.contains("\"cipher\": \"none\""), meta)
    }

    @Test
    fun `purge narrows by insurer and insurant, and clears tokens on their own`(@TempDir dir: Path) {
        openCache(dir.resolve("c.db")).use { (_, db) ->
            db.store(key(insurant = "a"), bundle())
            db.store(key(insurant = "b"), bundle())

            assertEquals(1, db.purge(insurantId = "a"))
            assertNull(db.lookup(key(insurant = "a")))
            assertNotNull(db.lookup(key(insurant = "b")))

            assertEquals(1, db.clearPoppTokens())
            assertNull(db.lookup(key(insurant = "b"))?.poppToken)
            assertNotNull(db.lookup(key(insurant = "b")), "the bundle itself survives")

            assertEquals(1, db.purge())
            assertEquals(0, db.entryCount())
        }
    }

    @Test
    fun `two identities reading the same record are kept and purged apart`(@TempDir dir: Path) {
        openCache(dir.resolve("c.db")).use { (_, db) ->
            db.store(key(actor = "actor-a"), bundle(body = "for a"))
            db.store(key(actor = "actor-b"), bundle(body = "for b"))

            assertEquals(2, db.entryCount(), "same record, two readers, two rows")
            assertEquals(2, db.actorCount())
            assertArrayEquals("for a".toByteArray(), db.lookup(key(actor = "actor-a"))!!.body)

            assertEquals(1, db.purge(actorId = "actor-a"))
            assertNull(db.lookup(key(actor = "actor-a")))
            assertNotNull(db.lookup(key(actor = "actor-b")), "the other tenant is untouched")
        }
    }

    @Test
    fun `clearing tokens narrows to one identity like purge does`(@TempDir dir: Path) {
        openCache(dir.resolve("c.db")).use { (_, db) ->
            db.store(key(actor = "actor-a"), bundle(token = "popp-a"))
            db.store(key(actor = "actor-b"), bundle(token = "popp-b"))

            assertEquals(1, db.clearPoppTokens(actorId = "actor-a"))
            assertNull(db.lookup(key(actor = "actor-a"))?.poppToken)
            assertEquals("popp-b", db.lookup(key(actor = "actor-b"))?.poppToken)
        }
    }

    @Test
    fun `a cache written by another schema is recreated, not left silently broken`(@TempDir dir: Path) {
        val file = dir.resolve("c.db")
        openCache(file).use { (_, db) -> db.store(key(), bundle()) }
        // What a build with a different table shape leaves behind: our file, someone else's schema.
        DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use { c ->
            c.createStatement().use { it.execute("PRAGMA user_version=99") }
        }

        openCache(file).use { (_, db) ->
            assertNull(db.lookup(key()))
            db.store(key(), bundle())
            assertNotNull(db.lookup(key()), "the recreated file is writable")
        }
    }

    @Test
    fun `the file and its WAL sidecars are owner-only`(@TempDir dir: Path) {
        val file = dir.resolve("c.db")
        openCache(file).use { (_, db) -> db.store(key(), bundle()) }
        listOf(file, Path.of("$file-wal"), Path.of("$file-shm"))
            .filter { Files.exists(it) }
            .forEach { assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(it)), it.toString()) }
        assertEquals(
            "rw-------",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("c.db.meta.json"))),
        )
    }

    private class NamedCodec(override val name: String) : BlobCodec {
        override fun encode(plain: ByteArray) = plain
        override fun decode(stored: ByteArray) = stored
    }
}