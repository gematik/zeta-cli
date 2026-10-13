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
