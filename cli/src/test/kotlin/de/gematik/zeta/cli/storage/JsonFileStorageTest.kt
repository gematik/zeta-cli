package de.gematik.zeta.cli.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JsonFileStorageTest {

    @Test
    fun `put then get round-trips a value`(@TempDir dir: Path) = runBlocking {
        val s = JsonFileStorage(dir.resolve("test.json"))
        s.put("foo", "bar")
        assertEquals("bar", s.get("foo"))
    }

    @Test
    fun `value persists across instances on the same path`(@TempDir dir: Path) = runBlocking {
        val path = dir.resolve("test.json")
        JsonFileStorage(path).put("foo", "bar")
        // Fresh instance — has to re-read from disk.
        assertEquals("bar", JsonFileStorage(path).get("foo"))
    }

    @Test
    fun `remove deletes the entry and persists the deletion`(@TempDir dir: Path) = runBlocking {
        val path = dir.resolve("test.json")
        val s = JsonFileStorage(path)
        s.put("foo", "bar")
        s.remove("foo")
        assertNull(s.get("foo"))
        assertNull(JsonFileStorage(path).get("foo"))
    }

    @Test
    fun `clear empties storage and rewrites the file`(@TempDir dir: Path) = runBlocking {
        val path = dir.resolve("test.json")
        val s = JsonFileStorage(path)
        s.put("a", "1")
        s.put("b", "2")
        s.clear()
        assertNull(s.get("a"))
        assertNull(s.get("b"))
        // kotlinx-serialization renders an empty map as `{}` (no inner newline) even with
        // prettyPrint = true, so just assert the file holds an empty object.
        assertEquals("{}", path.readText().trim())
    }

    @Test
    fun `parent directory is created on first write`(@TempDir dir: Path) = runBlocking {
        val path = dir.resolve("nested/deep/path/test.json")
        JsonFileStorage(path).put("foo", "bar")
        assertTrue(Files.exists(path), "expected $path to exist")
    }

    @Test
    fun `file is created with mode 0600 on POSIX filesystems`(@TempDir dir: Path) = runBlocking {
        val path = dir.resolve("test.json")
        if (!path.fileSystem.supportedFileAttributeViews().contains("posix")) {
            // Windows / non-POSIX FS — nothing to assert. Skip cleanly.
            return@runBlocking
        }
        JsonFileStorage(path).put("foo", "bar")
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(path),
        )
    }

    @Test
    fun `output is pretty-printed JSON`(@TempDir dir: Path) = runBlocking {
        val path = dir.resolve("test.json")
        val s = JsonFileStorage(path)
        s.put("a", "1")
        s.put("b", "2")
        // Pretty-print → newlines + 2-space indent. Tolerate trailing newline differences.
        val text = path.readText()
        assertTrue("\n" in text, "expected pretty-printed output, got: $text")
        assertTrue("  \"a\": \"1\"" in text, "expected indented entries, got: $text")
    }

    @Test
    fun `corrupt JSON surfaces as JsonFileStorageException`(@TempDir dir: Path) {
        val path = dir.resolve("test.json")
        path.writeText("{not valid json")
        assertThrows(JsonFileStorageException::class.java) {
            runBlocking { JsonFileStorage(path).get("foo") }
        }
    }

    @Test
    fun `concurrent puts all land via the mutex`(@TempDir dir: Path) = runBlocking {
        val s = JsonFileStorage(dir.resolve("test.json"))
        coroutineScope {
            (1..50)
                .map { i -> async { s.put("k$i", "v$i") } }
                .awaitAll()
        }
        for (i in 1..50) assertEquals("v$i", s.get("k$i"))
    }

    @Test
    fun `temp files are cleaned up on success`(@TempDir dir: Path) = runBlocking {
        val path = dir.resolve("test.json")
        JsonFileStorage(path).put("foo", "bar")
        val leftover = Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().contains(".tmp.") }.count()
        }
        assertEquals(0, leftover, "expected no .tmp.* leftovers in $dir")
    }
}
