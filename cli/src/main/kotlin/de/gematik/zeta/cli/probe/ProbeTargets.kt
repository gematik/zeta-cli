package de.gematik.zeta.cli.probe

import com.github.ajalt.clikt.core.UsageError
import de.gematik.zeta.catalog.CachedCatalog
import de.gematik.zeta.catalog.CatalogStore
import de.gematik.zeta.catalog.Environment
import de.gematik.zeta.catalog.ServiceCatalog
import de.gematik.zeta.cli.client.originOf
import io.ktor.http.Url
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json

/**
 * One probed Zeta resource: its environment, origin (RFC 8707 resource indicator), the scopes to
 * request, and the two slugs telemetry is keyed by — [service] is the instance (the catalog's instance
 * name such as `vsdm-1`, `popp`, an `--endpoint-name`, or a host name) and [type] the service kind
 * (the catalog's `type` such as `vsdm`, `popp`, or an `--endpoint-type`). [labels] are the caller's
 * extra labels for that type.
 */
internal data class ProbeTarget(
    val env: Environment,
    val resource: String,
    val scopes: List<String>,
    val service: String,
    val type: String,
    val labels: Map<String, String> = emptyMap(),
) {
    val envLabel: String get() = env.name.lowercase()
    val key: String get() = "$envLabel|$resource|${scopes.joinToString(",")}"
}

internal const val POPP_SERVICE = "popp"

/** One `--endpoint` with its positional `--endpoint-scope` and the optional `--endpoint-name` / `--endpoint-type`. */
internal data class ExplicitEndpoint(val url: String, val scope: String, val name: String? = null, val type: String? = null)

/**
 * Pair the i-th `--endpoint` with the i-th `--endpoint-scope`, `--endpoint-name` and `--endpoint-type`.
 * There is no default scope: a resource's scope is part of its identity (it selects the token, the
 * storage slice, the AS grant), so guessing one would silently probe the wrong thing. Names and types
 * are optional as a whole; when given, their count must match too.
 */
internal fun pairEndpoints(
    urls: List<String>,
    scopes: List<String>,
    names: List<String> = emptyList(),
    types: List<String> = emptyList(),
): List<ExplicitEndpoint> {
    if (urls.size != scopes.size) {
        throw UsageError(
            "--endpoint and --endpoint-scope must be given the same number of times " +
                "(${urls.size} endpoint(s) vs ${scopes.size} scope(s)); the i-th scope belongs to the i-th endpoint",
        )
    }
    fun optional(list: List<String>, option: String): List<String?> {
        if (list.isEmpty()) return List(urls.size) { null }
        if (list.size != urls.size) {
            throw UsageError("$option must be given once per --endpoint or not at all (${urls.size} endpoint(s) vs ${list.size})")
        }
        return list
    }
    val duplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    if (duplicates.isNotEmpty()) throw UsageError("--endpoint-name must be unique: ${duplicates.joinToString()}")
    val name = optional(names, "--endpoint-name")
    val type = optional(types, "--endpoint-type")
    return urls.indices.map { ExplicitEndpoint(urls[it], scopes[it], name[it], type[it]) }
}

/**
 * One environment's target list: the catalog's VSDM instances (scope `vsdservice`, slug = instance
 * name, type from the catalog) and the PoPP service (slug and type `popp`) when [includeCatalog], then
 * the explicit [extras]; each URL reduced to its resource origin, duplicates dropped, catalog order
 * first. [typeLabels] are attached by type.
 */
internal fun resolveTargets(
    env: Environment,
    catalog: ServiceCatalog?,
    poppUrl: String,
    includeCatalog: Boolean,
    extras: List<ExplicitEndpoint>,
    typeLabels: Map<String, Map<String, String>> = emptyMap(),
): List<ProbeTarget> {
    fun target(resource: String, scopes: List<String>, service: String, type: String) =
        ProbeTarget(env, resource, scopes, service, type, typeLabels[type].orEmpty())
    val all = buildList {
        if (includeCatalog) {
            catalog?.serviceInstances?.forEach { (name, instance) ->
                if (instance.type == "vsdm") add(target(originOf(instance.url), listOf("vsdservice"), name, instance.type))
            }
            add(target(originOf(poppUrl), listOf(POPP_SERVICE), POPP_SERVICE, POPP_SERVICE))
        }
        extras.forEach { extra ->
            if ("://" !in extra.url) throw UsageError("--endpoint ${extra.url} is not an absolute URL (expected scheme://host[:port]/…)")
            val origin = runCatching { originOf(extra.url) }
                .getOrElse { throw UsageError("--endpoint ${extra.url} is not a valid URL: ${it.message}") }
            val slug = extra.name ?: Url(origin).host
            add(target(origin, listOf(extra.scope), slug, extra.type ?: slug))
        }
    }
    return all.distinctBy { it.key }
}

/**
 * Concatenate the per-environment lists in `--probe-env` order. The same `(resource, scopes)` in two
 * environments is refused: the SDK keys its state slice by resource and scopes only, so two such targets
 * would share one token store.
 */
internal fun mergeEnvTargets(perEnv: List<List<ProbeTarget>>): List<ProbeTarget> {
    val all = perEnv.flatten()
    all.groupBy { "${it.resource}|${it.scopes.joinToString(",")}" }.values
        .firstOrNull { group -> group.map { it.env }.distinct().size > 1 }
        ?.let { group ->
            throw UsageError(
                "${group.first().resource} ${group.first().scopes} is a target in several environments " +
                    "(${group.map { it.envLabel }}); the SDK state store cannot tell them apart",
            )
        }
    return all
}

private val LABEL_NAME = Regex("[a-zA-Z_][a-zA-Z0-9_]*")

/** Label names the prober sets itself; a `--type-label` must not shadow them. */
internal val RESERVED_LABELS = setOf("env", "service", "type", "endpoint", "probe", "step", "result", "error_type", "fallback")

/** `TYPE:KEY=VALUE` items → type → (key → value); keys must be Prometheus label names. */
internal fun parseTypeLabels(items: List<String>): Map<String, Map<String, String>> {
    val out = mutableMapOf<String, MutableMap<String, String>>()
    items.forEach { raw ->
        val colon = raw.indexOf(':')
        val eq = if (colon < 0) -1 else raw.indexOf('=', colon + 1)
        if (colon <= 0 || eq <= colon + 1 || eq == raw.length - 1) {
            throw UsageError("--type-label expects TYPE:KEY=VALUE, got '$raw'")
        }
        val type = raw.substring(0, colon)
        val key = raw.substring(colon + 1, eq)
        val value = raw.substring(eq + 1)
        if (!LABEL_NAME.matches(key) || key.startsWith("__")) throw UsageError("--type-label key '$key' is not a valid label name")
        if (key in RESERVED_LABELS) throw UsageError("--type-label key '$key' is reserved (set by the prober itself)")
        val previous = out.getOrPut(type) { mutableMapOf() }.put(key, value)
        if (previous != null && previous != value) {
            throw UsageError("--type-label $type:$key given twice with different values ('$previous' vs '$value')")
        }
    }
    return out
}

/** `dev,ref` / `dev ref` → environments, order kept, duplicates dropped. */
internal fun parseEnvironments(raw: String): List<Environment> {
    val envs = raw.split(Regex("[,\\s]+")).filter { it.isNotBlank() }.map { name ->
        Environment.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw IllegalArgumentException("unknown environment '$name' (expected dev, ref, test or prod)")
    }.distinct()
    require(envs.isNotEmpty()) { "at least one environment is required" }
    return envs
}

/** When the catalog copy in [store] was fetched from the network, or null when there is none. */
internal fun cachedCatalogFetchedAt(store: CatalogStore, env: Environment): Long? =
    store.read(env)?.let { text ->
        runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(CachedCatalog.serializer(), text).fetchedAtEpochSec }.getOrNull()
    }

/**
 * Round-robin step that survives the list changing underneath it: the element after [lastKey],
 * wrapping; the first element when [lastKey] is unknown (never probed, or evicted); null when empty.
 */
internal fun nextTarget(targets: List<ProbeTarget>, lastKey: String?): ProbeTarget? {
    if (targets.isEmpty()) return null
    val last = if (lastKey == null) -1 else targets.indexOfFirst { it.key == lastKey }
    return targets[(last + 1) % targets.size]
}

/**
 * How long to wait between two consecutive probe launches so that every target is probed once per
 * [interval] without bursting: `interval / n` (60s over 20 targets → one probe every 3s), never below
 * [minTick]; the whole interval when there is nothing to probe.
 */
internal fun tickInterval(interval: Duration, targetCount: Int, minTick: Duration = 1.seconds): Duration =
    if (targetCount <= 0) interval else maxOf(interval / targetCount, minTick)

/** The live target list: a volatile snapshot the scheduler reads per tick and the catalog refresh swaps. */
internal class TargetRegistry(initial: List<ProbeTarget>) {
    @Volatile
    var current: List<ProbeTarget> = initial
        private set

    /** Replace the list; returns `(added, removed)` so callers can log and tear down evicted sessions. */
    fun update(next: List<ProbeTarget>): Pair<List<ProbeTarget>, List<ProbeTarget>> {
        val before = current.map { it.key }.toSet()
        val after = next.map { it.key }.toSet()
        val removed = current.filter { it.key !in after }
        current = next
        return next.filter { it.key !in before } to removed
    }
}
