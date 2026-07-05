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
            s.put("at:abc", "access")
            s.put("rt:abc", "refresh")
            s.put("exp:abc", "123")
            s.put("hash_index", "abc")
            s.put("asl_session_by_fqdnabc", "session")
            s.put("asl_hash_index_key", "abc")
            s.put("client_registration_by_auth_server", "{...}")
            s.put("client_private_key", "key")

            StateExpiry(db).expireAll("c1")

            assertNull(s.get("at:abc"))
            assertNull(s.get("rt:abc"))
            assertNull(s.get("exp:abc"))
            assertNull(s.get("asl_session_by_fqdnabc"))
            assertNull(s.get("asl_hash_index_key"))
            assertEquals("{...}", s.get("client_registration_by_auth_server"))
            assertEquals("key", s.get("client_private_key"))
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
