package de.gematik.zeta.cli.probe

import com.github.ajalt.clikt.core.UsageError
import de.gematik.zeta.catalog.ServiceCatalog
import de.gematik.zeta.cli.client.originOf
import io.ktor.http.Url
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One probed Zeta resource: its origin (RFC 8707 resource indicator), the scopes to request, and the
 * [service] slug telemetry is keyed by — the catalog's instance name for a VSDM service (the name its
 * routing table points at), `popp` for the PoPP service, the host name for an explicit `--endpoint`.
 */
internal data class ProbeTarget(val resource: String, val scopes: List<String>, val service: String) {
    val key: String get() = "$resource|${scopes.joinToString(",")}"
}

internal const val POPP_SERVICE = "popp"

/**
 * Pair the i-th `--endpoint` with the i-th `--endpoint-scope`. There is no default scope: a resource's
 * scope is part of its identity (it selects the token, the storage slice, the AS grant), so guessing one
 * would silently probe the wrong thing.
 */
internal fun pairEndpoints(urls: List<String>, scopes: List<String>): List<Pair<String, String>> {
    if (urls.size != scopes.size) {
        throw UsageError(
            "--endpoint and --endpoint-scope must be given the same number of times " +
                "(${urls.size} endpoint(s) vs ${scopes.size} scope(s)); the i-th scope belongs to the i-th endpoint",
        )
    }
    return urls.zip(scopes)
}

/**
 * The target list: the env's catalog VSDM instances (scope `vsdservice`, slug = instance name) and the
 * PoPP service (scope `popp`) when [includeCatalog], then the explicit [extras] as `(url, scope)`; each
 * URL reduced to its resource origin, duplicates dropped, catalog order first.
 */
internal fun resolveTargets(
    catalog: ServiceCatalog?,
    poppUrl: String,
    includeCatalog: Boolean,
    extras: List<Pair<String, String>>,
): List<ProbeTarget> {
    val all = buildList {
        if (includeCatalog) {
            catalog?.serviceInstances?.forEach { (name, instance) ->
                if (instance.type == "vsdm") add(ProbeTarget(originOf(instance.url), listOf("vsdservice"), name))
            }
            add(ProbeTarget(originOf(poppUrl), listOf(POPP_SERVICE), POPP_SERVICE))
        }
        extras.forEach { (url, scope) ->
            if ("://" !in url) throw UsageError("--endpoint $url is not an absolute URL (expected scheme://host[:port]/…)")
            val origin = runCatching { originOf(url) }
                .getOrElse { throw UsageError("--endpoint $url is not a valid URL: ${it.message}") }
            add(ProbeTarget(origin, listOf(scope), Url(origin).host))
        }
    }
    return all.distinctBy { it.key }
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
