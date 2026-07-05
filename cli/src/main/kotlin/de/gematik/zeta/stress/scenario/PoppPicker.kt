package de.gematik.zeta.stress.scenario

import de.gematik.zeta.stress.db.PoppStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hands out a PoPP token per attempt for `login-and-vsdm-storm`. A client cycles round-robin through
 * the tokens bound to its own identity (rotating patients across attempts for realism). The token must
 * always match the authenticated Telematik-ID — there is no cross-identity borrowing — so an identity
 * with no bound token yields null, and the attempt is recorded as a failure. Token lists are read from
 * [store] once per identity and cached.
 */
class PoppPicker(private val store: PoppStore) {
    private val perIdentity = ConcurrentHashMap<String, List<String>>()
    private val cursors = ConcurrentHashMap<String, AtomicInteger>()

    fun next(telematikId: String): String? {
        val own = perIdentity.getOrPut(telematikId) { store.forIdentity(telematikId) }
        if (own.isEmpty()) return null
        val i = cursors.getOrPut(telematikId) { AtomicInteger(0) }.getAndIncrement()
        return own[Math.floorMod(i, own.size)]
    }
}
