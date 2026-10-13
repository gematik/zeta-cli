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
package de.gematik.zeta.cli

import de.gematik.zeta.logging.Log
import de.gematik.zeta.logging.ZetaLogLevel
import de.gematik.zeta.logging.ZetaLogger
import org.slf4j.LoggerFactory

/**
 * Bridges the SDK's own [Log] object into our slf4j/logback pipeline.
 *
 * The SDK doesn't log through slf4j — it has its own [Log] singleton that defaults to level `ERROR`
 * and, with no logger attached, prints straight to **stdout**. So `-v` (which only lowers the logback
 * root level) never surfaces the SDK's own INFO/DEBUG — the ASL handshake, token exchange, etc. — and
 * whatever does fire pollutes stdout. [install] attaches this bridge and opens the SDK log's gate to
 * match the CLI verbosity, so those lines appear on the same stderr appender as everything else.
 */
internal object SdkLogBridge : ZetaLogger {
    private val log = LoggerFactory.getLogger("de.gematik.zeta.sdk")

    /** Route the SDK log through logback and match its level to the CLI verbosity (only called for `-v`+). */
    fun install(verbosity: Int) {
        Log.setLogger(this)
        Log.setLogLevel(if (verbosity >= 2) ZetaLogLevel.DEBUG else ZetaLogLevel.INFO)
    }

    private fun render(tag: String?, message: () -> String): String =
        tag?.let { "[$it] ${message()}" } ?: message()

    override fun d(tag: String?, message: () -> String, throwable: Throwable?) {
        if (!log.isDebugEnabled) return
        val m = render(tag, message)
        if (throwable != null) log.debug(m, throwable) else log.debug(m)
    }

    override fun i(tag: String?, message: () -> String, throwable: Throwable?) {
        if (!log.isInfoEnabled) return
        val m = render(tag, message)
        if (throwable != null) log.info(m, throwable) else log.info(m)
    }

    override fun w(tag: String?, message: () -> String, throwable: Throwable?) {
        if (!log.isWarnEnabled) return
        val m = render(tag, message)
        if (throwable != null) log.warn(m, throwable) else log.warn(m)
    }

    override fun e(tag: String?, message: () -> String, throwable: Throwable?) {
        if (!log.isErrorEnabled) return
        val m = render(tag, message)
        if (throwable != null) log.error(m, throwable) else log.error(m)
    }
}
