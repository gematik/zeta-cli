package de.gematik.zeta.cli.config

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.Option
import com.github.ajalt.clikt.sources.ValueSource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable
import java.nio.file.Path

private val log = KotlinLogging.logger {}

/**
 * Clikt [ValueSource] backed by a TOML file (tomlj). The file is parsed once on first use;
 * parse errors surface as [TomlConfigException] from the first option lookup so they show up
 * in the user's terminal with file:line context, not as a stack trace at startup.
 *
 * ## Lookup precedence (per option)
 *
 *   1. `Option.valueSourceKey` if explicitly set (matches Clikt's [com.github.ajalt.clikt.sources.MapValueSource]).
 *   2. Subcommand-scoped key: `<subcommand>.<option-name>` (e.g. `http.request`).
 *   3. Top-level key: `<option-name>`.
 *
 * The two-level lookup means options that live on a base class shared by several subcommands
 * (e.g. `--connector-telematik-id` on `ZetaSessionCommand`) can be set once at the top of the
 * file rather than under both `[http]` and `[ws]`. The scoped form still wins per-section.
 *
 * ## Multi-valued options
 *
 * TOML arrays map 1:1 to Clikt invocations. So:
 * ```toml
 * [http]
 * header = ["X-Foo: bar", "X-Baz: qux"]
 * ```
 * yields two `-H` invocations. Booleans/numbers are stringified to "true"/"false"/"123" so
 * they slot into Clikt's string-based value parsing.
 *
 * ## Caveat: sticky options + value source
 *
 * Options declared on [de.gematik.zeta.cli.ZetaCliktCommand] are inherited by every command in
 * the chain (`-v`, `--connector-config`, …). When `zeta.toml` provides a value AND the user
 * passes the CLI flag at a parent depth (e.g. `zeta --connector-config=X connector inspect`),
 * the child re-reads the same TOML key into its own option and overwrites the value the parent
 * already merged into [de.gematik.zeta.cli.CliConfig]. Workaround: pass the CLI flag at the
 * subcommand depth (`zeta connector inspect --connector-config=X`), where it wins as expected.
 * This is a pre-existing limitation of the chain-merge pattern, exposed (not caused) by the
 * value source.
 */
class TomlValueSource(private val path: Path) : ValueSource {

    private val table: TomlTable by lazy {
        log.debug { "Loading config from $path" }
        val result = Toml.parse(path)
        if (result.hasErrors()) {
            val msg = result.errors().joinToString(separator = "\n  ", prefix = "  ") {
                "${it.position()}: ${it.message}"
            }
            throw TomlConfigException("Failed to parse $path:\n$msg")
        }
        result
    }

    override fun getValues(context: Context, option: Option): List<ValueSource.Invocation> {
        option.valueSourceKey?.let { return lookup(it).orEmpty() }

        val name = ValueSource.name(option)
        val scopedPath = context.commandNameWithParents().drop(1) + name
        if (scopedPath.size > 1) {
            lookup(scopedPath.joinToString("."))?.let { return it }
        }
        return lookup(name).orEmpty()
    }

    /** `null` if the key is absent or refers to a sub-table; otherwise one or more invocations. */
    private fun lookup(key: String): List<ValueSource.Invocation>? {
        if (!table.contains(key)) return null
        val raw = table.get(key) ?: return null
        return when (raw) {
            is TomlTable -> null
            is TomlArray -> {
                if (raw.isEmpty) return null
                (0 until raw.size()).map { idx ->
                    invocation(raw.get(idx), "$path:$key[$idx]")
                }
            }
            else -> listOf(invocation(raw, "$path:$key"))
        }
    }

    private fun invocation(raw: Any?, location: String): ValueSource.Invocation =
        ValueSource.Invocation(values = listOf(toScalarString(raw)), location = location)

    private fun toScalarString(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        // tomlj returns Long/Double for numerics, Boolean for bools — stringify uniformly so
        // Clikt's per-option converter (.int(), .flag(), …) receives a plain string.
        else -> v.toString()
    }
}

class TomlConfigException(message: String) : RuntimeException(message)
