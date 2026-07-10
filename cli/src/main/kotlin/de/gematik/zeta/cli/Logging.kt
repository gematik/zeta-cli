package de.gematik.zeta.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal object Logging {
    fun applyVerbosity(count: Int) {
        if (count <= 0) return
        val level = when (count) {
            1 -> Level.INFO
            2 -> Level.DEBUG
            else -> Level.TRACE
        }
        val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).level = level
        SdkLogBridge.install(count)
    }
}
