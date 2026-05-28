package de.gematik.zeta.cli

/**
 * Pre-Clikt argv scanner. Used for options that need to be resolved *before* Clikt parses
 * — currently `-f/--file` (selects the YAML value source path) and indirectly the bare
 * `--trace` presence check. Both must wire infrastructure (config-file value source /
 * tracer) into the Clikt context before any subcommand dispatches.
 *
 * Recognises `-n VALUE`, `-n=VALUE`, `--name VALUE`, `--name=VALUE` for each given option
 * spelling. First match wins. POSIX bundling (`-fX`) is intentionally not supported — no
 * pre-parse option is bundleable, and we don't want to ambiguously chew letters out of
 * forms like `-vvv`.
 */
internal fun sniffOptValue(args: Array<String>, vararg names: String): String? {
    for (i in args.indices) {
        val a = args[i]
        for (name in names) {
            if (a == name && i + 1 < args.size) return args[i + 1]
            val eq = "$name="
            if (a.startsWith(eq)) return a.substring(eq.length)
        }
    }
    return null
}
