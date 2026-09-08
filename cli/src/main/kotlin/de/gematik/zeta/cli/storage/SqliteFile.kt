package de.gematik.zeta.cli.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection

/** "ZETA" as a big-endian ASCII quad, the conventional way to pick a SQLite `application_id`. */
const val ZETA_APPLICATION_ID = 0x5A455441

private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")

/**
 * Owner-only permissions on a SQLite file *and* its `-wal` / `-shm` sidecars. Under
 * `journal_mode=WAL` the most recent writes live in the WAL, so restricting only the main file
 * leaves them readable by everyone. Best-effort: a filesystem without POSIX permissions keeps its own.
 */
fun restrictSqliteFile(path: Path) {
    listOf(path, Path.of("$path-wal"), Path.of("$path-shm")).forEach {
        runCatching { Files.setPosixFilePermissions(it, OWNER_ONLY) }
    }
}

/** Create [path] owner-only before SQLite opens it, so its content is never briefly world-readable. */
fun createSqliteFileOwnerOnly(path: Path) {
    runCatching { Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY)) }
}

/** The file header as (`application_id`, `user_version`); both are 0 on a database nobody stamped. */
fun readSqliteHeader(c: Connection): Pair<Int, Int> = c.createStatement().use { st ->
    val applicationId = st.executeQuery("PRAGMA application_id").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
    val userVersion = st.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
    applicationId to userVersion
}

/** Claim the file as ours and record which schema wrote it. */
fun stampSqliteHeader(c: Connection, schemaVersion: Int) {
    c.createStatement().use { st ->
        st.execute("PRAGMA application_id=$ZETA_APPLICATION_ID")
        st.execute("PRAGMA user_version=$schemaVersion")
    }
}

/**
 * True when the failure says the file is not a SQLite database at all. A locked, busy or
 * permission-denied file fails differently and must never be treated as garbage to discard.
 */
fun isNotADatabase(e: Throwable): Boolean =
    generateSequence(e) { it.cause }.any { it.message?.contains("not a database", ignoreCase = true) == true }
