package de.gematik.zeta.cli.storage

import de.gematik.zeta.sdk.storage.SdkStorage
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

private val log = KotlinLogging.logger {}

/**
 * `SdkStorage` backed by a single JSON file holding a flat `{ "key": "value" }` object.
 *
 * Designed for the zeta CLI's persistent state at `$XDG_CONFIG_HOME/telematik/zeta/<profile>.json`.
 *
 * Properties:
 * - **Plaintext.** No encryption layer here — pair with a secret-aware wrapper to keep
 *   sensitive values (tokens, private keys) out of this file.
 * - **Atomic writes.** Every mutation writes to a sibling temp file with a UUID-suffixed
 *   name, fsyncs the channel, and renames over the target with `ATOMIC_MOVE`. A torn
 *   write is observable as either old or new content, never partial.
 * - **Mode 0600 on POSIX.** Created with `OWNER_READ | OWNER_WRITE` only so other users
 *   on the box can't peek even before any encryption layer is added on top. Falls back
 *   to default permissions on filesystems without POSIX support.
 * - **In-process serial.** A coroutine [Mutex] serialises mutations from the same JVM.
 *   Cross-process writes are last-write-wins, which is acceptable for a single-user CLI.
 * - **Pretty-printed.** Output is `prettyPrint = true` for human inspection / `git diff`.
 *
 * The first read loads the file (or initialises to empty) and caches the result; later
 * `get`s do not re-read from disk. Cross-process writes during the lifetime of a single
 * instance are not observed — fine for `register-then-exit`-style invocations.
 */
class JsonFileStorage(private val path: Path) : SdkStorage {
    private val mutex = Mutex()
    private val supportsPosix: Boolean =
        path.fileSystem.supportedFileAttributeViews().contains("posix")

    @Volatile
    private var cache: Map<String, String>? = null

    override suspend fun put(key: String, value: String) = mutex.withLock {
        val current = ensureLoaded()
        val isUpdate = key in current
        val next = LinkedHashMap(current).apply { put(key, value) }
        writeAtomic(next)
        cache = next
        // Value length is useful for diagnosing surprises (truncation, double-encoding) but
        // the value itself can be a token/secret — never log it directly.
        log.debug { "${if (isUpdate) "Updated" else "Added"} key '$key' (${value.length} chars) in $path" }
    }

    override suspend fun get(key: String): String? = mutex.withLock {
        val value = ensureLoaded()[key]
        log.debug { "Read key '$key' from $path: ${if (value == null) "MISS" else "HIT (${value.length} chars)"}" }
        value
    }

    override suspend fun remove(key: String) = mutex.withLock {
        val current = ensureLoaded()
        if (key !in current) {
            log.debug { "Remove of '$key' in $path: no-op (not present)" }
            return@withLock
        }
        val next = LinkedHashMap(current).apply { remove(key) }
        writeAtomic(next)
        cache = next
        log.debug { "Removed key '$key' from $path" }
    }

    override suspend fun clear() = mutex.withLock {
        val sizeBefore = cache?.size
        writeAtomic(emptyMap())
        cache = emptyMap()
        log.debug { "Cleared $path (was ${sizeBefore ?: "unknown"} entries)" }
    }

    private fun ensureLoaded(): Map<String, String> {
        cache?.let { return it }
        val loaded: Map<String, String> = if (path.exists()) {
            log.debug { "Loading storage from $path" }
            try {
                val map = json.decodeFromString<Map<String, String>>(path.readText())
                log.debug { "Loaded ${map.size} entries from $path: keys=${map.keys}" }
                map
            } catch (e: Exception) {
                throw JsonFileStorageException(
                    "could not parse storage file $path: ${e.message}",
                    cause = e,
                )
            }
        } else {
            log.debug { "Storage file $path does not exist; starting with empty state" }
            emptyMap()
        }
        cache = loaded
        return loaded
    }

    private fun writeAtomic(map: Map<String, String>) {
        val parent = path.toAbsolutePath().parent
            ?: error("storage path $path has no parent directory")
        if (!parent.exists()) {
            log.debug { "Creating parent directory $parent" }
            Files.createDirectories(parent)
        }

        val tmp = parent.resolve(".${path.fileName}.tmp.${UUID.randomUUID()}")
        val content = json.encodeToString<Map<String, String>>(map)
        log.debug { "Writing ${map.size} entries (${content.length} chars) to $path via $tmp" }
        try {
            openTempForWrite(tmp).use { channel ->
                channel.write(ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8)))
                // fsync the data + metadata so a kernel crash mid-rename can't leave the
                // tmp file containing zeroed content even after an "atomic" move succeeded.
                channel.force(true)
            }
            Files.move(tmp, path, ATOMIC_MOVE, REPLACE_EXISTING)
            log.debug { "Atomic move of $tmp -> $path complete" }
        } catch (e: Throwable) {
            log.debug(e) { "Write to $path failed; cleaning up $tmp" }
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }

    private fun openTempForWrite(tmp: Path): FileChannel {
        val opts = EnumSet.of(WRITE, CREATE_NEW)
        if (!supportsPosix) return FileChannel.open(tmp, opts)
        val attr = PosixFilePermissions.asFileAttribute(
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
        return FileChannel.open(tmp, opts, attr)
    }

    private companion object {
        // explicitNulls = false trims the `: null` tails kotlinx-serialization would emit
        // for nullable fields if we ever swap the value type. prettyPrint for human eyes.
        val json = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            explicitNulls = false
        }
    }
}

class JsonFileStorageException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
