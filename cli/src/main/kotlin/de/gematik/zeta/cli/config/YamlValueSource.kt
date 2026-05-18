package de.gematik.zeta.cli.config

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.Option
import com.github.ajalt.clikt.sources.ValueSource
import de.gematik.connector.expandEnvVars
import io.github.oshai.kotlinlogging.KotlinLogging
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.nio.file.Path
import kotlin.io.path.readText

private val log = KotlinLogging.logger {}

/**
 * Clikt [ValueSource] backed by a YAML file (SnakeYAML). Lazily loaded on first option
 * lookup. `${VAR}` placeholders in the raw text are expanded against the process
 * environment before parsing, identical to how [de.gematik.connector.parseDotkon]
 * handles `.kon` files — so secrets and per-host overrides can stay outside the file:
 *
 * ```yaml
 * connector-config: default
 * auth-method: connector
 * auth-connector-telematik-id: "${ZETA_AUTH_CONNECTOR_TELEMATIK_ID}"
 *
 * http:
 *   header:
 *     - "X-Trace-Id: dev"
 *   include: true
 *
 * ws:
 *   header:
 *     - "X-WS-Trace: dev"
 * ```
 *
 * ## Lookup precedence (per option)
 *
 *   1. `Option.valueSourceKey` if explicitly set (matches Clikt's [com.github.ajalt.clikt.sources.MapValueSource]).
 *   2. Subcommand-scoped key: `<subcommand>.<option-name>` (e.g. `http.request`).
 *   3. Top-level key: `<option-name>`.
 *
 * The two-level lookup means options that live on a base class shared by several
 * subcommands (e.g. `--auth-connector-telematik-id` on [de.gematik.zeta.cli.client.ZetaSessionCommand])
 * can be set once at the top of the file rather than under both `http:` and `ws:`. The
 * scoped form still wins per-section.
 *
 * ## Multi-valued options
 *
 * YAML lists map 1:1 to Clikt invocations: each list element becomes one
 * [ValueSource.Invocation]. Booleans/numbers are stringified to "true"/"false"/"123" so
 * they slot into Clikt's string-based per-option converter (`.int()`, `.flag()`, …).
 *
 * ## Caveat: sticky options + value source
 *
 * Options declared on [de.gematik.zeta.cli.ZetaCliktCommand] are inherited by every command
 * in the chain (`-v`, `--connector-config`, …). When the file provides a value AND the user
 * passes the CLI flag at a parent depth (`zeta --connector-config=X connector inspect`),
 * the child re-reads the same key into its own option and overwrites the value the parent
 * already merged into [de.gematik.zeta.cli.CliConfig]. Workaround: pass the CLI flag at the
 * subcommand depth (`zeta connector inspect --connector-config=X`), where it wins as
 * expected. Pre-existing limitation of the chain-merge pattern, exposed (not caused) by
 * the value source.
 */
class YamlValueSource(private val path: Path) : ValueSource {

    private val root: Map<String, Any?> by lazy {
        log.debug { "Loading config from $path" }
        val text = try {
            path.readText()
        } catch (e: Exception) {
            throw YamlConfigException("Could not read $path: ${e.message}", e)
        }
        val expanded = expandEnvVars(text)
        @Suppress("UNCHECKED_CAST")
        try {
            Yaml().load<Any?>(expanded) as? Map<String, Any?>
                ?: throw YamlConfigException("Top-level of $path must be a YAML mapping")
        } catch (e: YAMLException) {
            throw YamlConfigException("Failed to parse $path: ${e.message}", e)
        }
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

    /** `null` if the key is absent or refers to a sub-mapping; otherwise one or more invocations. */
    private fun lookup(dottedKey: String): List<ValueSource.Invocation>? {
        val parts = dottedKey.split('.')
        var node: Any? = root
        for (part in parts) {
            val map = node as? Map<*, *> ?: return null
            node = map[part] ?: return null
        }
        return when (node) {
            is Map<*, *> -> null
            is List<*> -> {
                if (node.isEmpty()) return null
                node.mapIndexed { idx, item ->
                    invocation(item, "$path:$dottedKey[$idx]")
                }
            }
            else -> listOf(invocation(node, "$path:$dottedKey"))
        }
    }

    private fun invocation(raw: Any?, location: String): ValueSource.Invocation =
        ValueSource.Invocation(values = listOf(toScalarString(raw)), location = location)

    /**
     * SnakeYAML returns Long/Double for numerics, Boolean for bools, String for strings,
     * `null` for explicit nulls. Stringify uniformly so Clikt's per-option converter
     * (`.int()`, `.flag()`, …) receives a plain string.
     */
    private fun toScalarString(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        else -> v.toString()
    }
}

class YamlConfigException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
