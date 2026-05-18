package de.gematik.zeta.cli.popp

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private val log = KotlinLogging.logger {}
private val kartosLog = KotlinLogging.logger("kartos.stderr")

/**
 * Spawns a `kartos pipe <image>` smartcard simulator child process backed by an XML card
 * image and exchanges single-line hex APDU/response pairs over its stdin/stdout pipes.
 * Stderr is consumed line-by-line on a daemon thread and forwarded to the SLF4J/Logback
 * pipeline at INFO under the `kartos.stderr` logger.
 *
 * The child process is reused for the whole popp scenario — card state needs to persist
 * across APDUs, so we open it once at the start of the flow and close it on completion.
 */
internal class KartosProcess private constructor(
    private val process: Process,
    private val stdoutReader: BufferedReader,
    private val stdinWriter: BufferedWriter,
    private val stderrThread: Thread,
) : Closeable {

    /**
     * Send one command APDU (hex string, no whitespace) and return kartos's response APDU
     * line. Throws [IOException] if kartos closed stdout before responding.
     */
    fun exchange(commandApduHex: String): String {
        log.debug { "kartos -> $commandApduHex" }
        try {
            stdinWriter.write(commandApduHex)
            stdinWriter.newLine()
            stdinWriter.flush()
        } catch (e: IOException) {
            throw IOException("kartos stdin write failed (${childStateDescription()})", e)
        }
        val line = stdoutReader.readLine()
            ?: throw IOException(
                "kartos closed stdout before responding to $commandApduHex (${childStateDescription()})",
            )
        val response = line.trim()
        log.debug { "kartos <- $response" }
        return response
    }

    private fun childStateDescription(): String =
        if (process.isAlive) "still running" else "exit code: ${process.exitValue()}"

    override fun close() {
        runCatching { stdinWriter.close() }
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            log.info { "kartos did not exit within 2s of stdin close; terminating" }
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }
        runCatching { stdoutReader.close() }
        runCatching { stderrThread.join(1_000) }
    }

    companion object {
        fun spawn(image: Path, executable: String = "kartos"): KartosProcess {
            val cmd = listOf(executable, "pipe", image.toString())
            log.info { "Spawning ${cmd.joinToString(" ")}" }
            val process = try {
                ProcessBuilder(cmd).redirectErrorStream(false).start()
            } catch (e: IOException) {
                throw IOException(
                    "Could not start '$executable' (is it on PATH?): ${e.message}",
                    e,
                )
            }
            val stdoutReader = process.inputStream.bufferedReader(StandardCharsets.UTF_8)
            val stdinWriter = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)
            val stderrReader = process.errorStream.bufferedReader(StandardCharsets.UTF_8)
            val stderrThread = thread(name = "kartos-stderr", isDaemon = true) {
                try {
                    stderrReader.forEachLine { kartosLog.info { it } }
                } catch (e: IOException) {
                    log.debug { "kartos stderr reader closed: ${e.message}" }
                }
            }
            return KartosProcess(process, stdoutReader, stdinWriter, stderrThread)
        }
    }
}
