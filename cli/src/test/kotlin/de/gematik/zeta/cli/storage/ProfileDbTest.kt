package de.gematik.zeta.cli.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProfileDbTest {

    @Test
    fun `the profile file and its WAL sidecars are owner-only`(@TempDir dir: Path) {
        val path = dir.resolve("default.storage.db")
        ProfileDb(path).putState("service-discovery", "dev", "{}")

        listOf(path, Path.of("$path-wal"), Path.of("$path-shm"))
            .filter { Files.exists(it) }
            .forEach {
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(it)), it.toString())
            }
    }

    @Test
    fun `a database belonging to another application is refused, not written into`(@TempDir dir: Path) {
        val path = dir.resolve("foreign.db")
        ProfileDb(path)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { c ->
            c.createStatement().use { it.execute("PRAGMA application_id=305419896") }
        }

        assertThrows(IllegalStateException::class.java) { ProfileDb(path) }
    }

    @Test
    fun `a profile written by an earlier build is adopted, never discarded`(@TempDir dir: Path) {
        val path = dir.resolve("legacy.db")
        // What every profile DB looked like before the header was stamped: our tables, no marker.
        java.sql.DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { c ->
            c.createStatement().use {
                it.executeUpdate(
                    "CREATE TABLE sdk_state (context TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY (context, key))",
                )
                it.executeUpdate("INSERT INTO sdk_state VALUES ('rs:example', 'at:1', 'token')")
            }
        }

        assertEquals("token", ProfileDb(path).getState("rs:example", "at:1"))
    }

    @Test
    fun `a file that is not a database at all still fails loudly`(@TempDir dir: Path) {
        val path = dir.resolve("garbage.db")
        path.writeText("not a database")

        assertThrows(Exception::class.java) { ProfileDb(path) }
    }
}
