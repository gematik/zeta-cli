package de.gematik.zeta.stress.scenario

import de.gematik.zeta.stress.db.PoppStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hands out a PoPP token per attempt for `login-and-vsdm-storm`. A client cycles round-robin
 * through the tokens bound to its own identity (rotating patients across attempts for realism); if its
 * identity has none bound, it draws from the global pool round-robin. Returns null only when there are
 * no tokens at all. Token lists are read from [store] once per identity and cached.
 */
class PoppPicker(private val store: PoppStore) {
    private val perIdentity = ConcurrentHashMap<String, List<String>>()
    private val cursors = ConcurrentHashMap<String, AtomicInteger>()
    private val pool: List<String> by lazy { store.any() }
    private val poolCursor = AtomicInteger(0)

    fun next(telematikId: String): String? {
        val own = perIdentity.getOrPut(telematikId) { store.forIdentity(telematikId) }
        if (own.isNotEmpty()) {
            val i = cursors.getOrPut(telematikId) { AtomicInteger(0) }.getAndIncrement()
            return own[Math.floorMod(i, own.size)]
        }
        if (pool.isEmpty()) return null
        return pool[Math.floorMod(poolCursor.getAndIncrement(), pool.size)]
    }
}
