package de.gematik.zeta.stress.storage

import de.gematik.zeta.stress.db.Db
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SqliteSdkStorageTest {

    @Test
    fun `round-trips values`(@TempDir dir: Path) = runBlocking {
        Db(dir.resolve("s.db")).use { db ->
            val s = SqliteSdkStorage("c1", db)
            assertNull(s.get("k"))
            s.put("k", "v")
            assertEquals("v", s.get("k"))
            s.put("k", "v2")
            assertEquals("v2", s.get("k"))
            s.remove("k")
            assertNull(s.get("k"))
        }
    }

    @Test
    fun `namespaces are isolated`(@TempDir dir: Path) = runBlocking {
        Db(dir.resolve("s.db")).use { db ->
            val a = SqliteSdkStorage("a", db)
            val b = SqliteSdkStorage("b", db)
            a.put("shared", "from-a")
            b.put("shared", "from-b")
            assertEquals("from-a", a.get("shared"))
            assertEquals("from-b", b.get("shared"))
            a.clear()
            assertNull(a.get("shared"))
            assertEquals("from-b", b.get("shared"))
        }
    }

    @Test
    fun `expireAll drops tokens and ASL but keeps registration and tpm key`(@TempDir dir: Path) = runBlocking {
        Db(dir.resolve("s.db")).use { db ->
            val s = SqliteSdkStorage("c1", db)
            // 1.2.2 layout: token/ASL value keys keep their literal prefix; index maps and the
            // resource-scoped registration keys are bare hashes that expiry must leave alone.
            s.put("at:abc", "access")
            s.put("rt:abc", "refresh")
            s.put("exp:abc", "123")
            s.put("auth0idx1", "{token-index map}")
            s.put("asl_session_by_resource:abc", "session")
            s.put("asl0idx1", "{asl-index map}")
            s.put("reg0hash1", "{registration}")
            s.put("pk00hash1", "key")

            StateExpiry(db).expireAll("c1")

            assertNull(s.get("at:abc"))
            assertNull(s.get("rt:abc"))
            assertNull(s.get("exp:abc"))
            assertNull(s.get("asl_session_by_resource:abc"))
            // index maps and registration/key rows survive — only the value keys are dropped.
            assertEquals("{token-index map}", s.get("auth0idx1"))
            assertEquals("{registration}", s.get("reg0hash1"))
            assertEquals("key", s.get("pk00hash1"))
        }
    }

    @Test
    fun `expireAccessTokenOnly keeps the refresh token`(@TempDir dir: Path) = runBlocking {
        Db(dir.resolve("s.db")).use { db ->
            val s = SqliteSdkStorage("c1", db)
            s.put("at:abc", "access")
            s.put("rt:abc", "refresh")
            s.put("exp:abc", "123")

            StateExpiry(db).expireAccessTokenOnly("c1")

            assertNull(s.get("at:abc"))
            assertNull(s.get("exp:abc"))
            assertEquals("refresh", s.get("rt:abc"))
        }
    }
}
