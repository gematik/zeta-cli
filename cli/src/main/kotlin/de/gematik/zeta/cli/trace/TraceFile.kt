/*
 * Copyright (Change Date see Readme), gematik GmbH
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the
 * European Commission – subsequent versions of the EUPL (the "Licence").
 * You may not use this work except in compliance with the Licence.
 *
 * You find a copy of the Licence in the "Licence" file or at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.
 * In case of changes by gematik GmbH find details in the "Readme" file.
 *
 * See the Licence for the specific language governing permissions and limitations under the Licence.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 */
package de.gematik.zeta.cli.trace

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.channels.Channels
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions

private val log = KotlinLogging.logger {}

/**
 * Appends finished span trees as JSON lines. Holds the exception type of a failure but never its
 * message, and is created owner-only where the file system can express it, because a daemon runs
 * against real cards and Konnektors.
 */
internal class TraceFile(
    private val path: Path,
    private val ownerOnly: Boolean = "posix" in FileSystems.getDefault().supportedFileAttributeViews(),
) {
    private val writeLock = Any()

    /** A trace that cannot be written costs a warning, never a request. */
    fun write(span: Span) {
        val line = treeJson(span).toString() + "\n"
        runCatching {
            synchronized(writeLock) {
                path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
                Files.newByteChannel(
                    path,
                    setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND),
                    *creationAttributes(),
                ).use { Channels.newOutputStream(it).write(line.toByteArray()) }
            }
        }.onFailure { log.warn { "could not write trace to $path: ${it.message}" } }
    }

    private fun creationAttributes(): Array<FileAttribute<*>> =
        if (ownerOnly) arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray()

    private fun treeJson(root: Span): JsonObject {
        val ageMs = (System.nanoTime() - root.startNanos) / 1_000_000
        return spanJson(root, root.startNanos, epochMs = System.currentTimeMillis() - ageMs)
    }

    private fun spanJson(span: Span, rootStartNanos: Long, epochMs: Long? = null): JsonObject = buildJsonObject {
        epochMs?.let { put("ts", it) }
        put("name", span.name)
        put("startMs", (span.startNanos - rootStartNanos) / 1_000_000.0)
        put("durationMs", span.durationMs)
        put("ok", span.ok)
        put("error", span.errorType)
        put("attrs", buildJsonObject { span.attrs.forEach { (k, v) -> put(k, attrJson(v)) } })
        put("children", buildJsonArray { span.children.forEach { add(spanJson(it, rootStartNanos)) } })
    }

    private fun attrJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
}
