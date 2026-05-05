package de.gematik.zeta.cli.term

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform

/**
 * Decides whether to emit ANSI colour escapes on stderr. Used by the Logback config
 * (via the `zeta.stderr.pattern` system property) and by [de.gematik.zeta.cli.http.CurlieLogging]
 * to keep `zeta 2> file` output plain text.
 *
 * Resolution order:
 *  1. `NO_COLOR` env var set (any non-empty value) → off (https://no-color.org).
 *  2. `FORCE_COLOR` env var set → on.
 *  3. `isatty(2)` via JNA → on iff stderr is a TTY.
 *  4. Detection failed (unsupported platform, JNA missing) → off.
 */
internal object StderrColors {
    val enabled: Boolean by lazy { detect() }

    private fun detect(): Boolean {
        if (!System.getenv("NO_COLOR").isNullOrEmpty()) return false
        if (!System.getenv("FORCE_COLOR").isNullOrEmpty()) return true
        return runCatching { isStderrTty() }.getOrDefault(false)
    }

    private fun isStderrTty(): Boolean =
        if (Platform.isWindows()) {
            Msvcrt.INSTANCE._isatty(STDERR_FD) != 0
        } else {
            PosixLibC.INSTANCE.isatty(STDERR_FD) == 1
        }

    private const val STDERR_FD = 2

    private interface PosixLibC : Library {
        fun isatty(fd: Int): Int

        companion object {
            val INSTANCE: PosixLibC = Native.load("c", PosixLibC::class.java)
        }
    }

    private interface Msvcrt : Library {
        fun _isatty(fd: Int): Int

        companion object {
            val INSTANCE: Msvcrt = Native.load("msvcrt", Msvcrt::class.java)
        }
    }
}
