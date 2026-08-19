package de.gematik.zeta.cli.storage

import de.gematik.zeta.catalog.CatalogStore
import de.gematik.zeta.catalog.Environment

/**
 * [CatalogStore] backed by a profile's [ProfileDb]: the service-discovery cache lives in the same
 * `<profile>.storage.db` as the rest of the CLI's state, under a reserved `sdk_state` context that
 * can't collide with a resource scope. Best-effort — a read or write failure degrades to a cache
 * miss rather than failing the command.
 */
class ProfileDbCatalogStore(private val db: ProfileDb) : CatalogStore {
    override fun read(env: Environment): String? =
        runCatching { db.getState(CONTEXT, env.name.lowercase()) }.getOrNull()

    override fun write(env: Environment, value: String) {
        runCatching { db.putState(CONTEXT, env.name.lowercase(), value) }
    }

    private companion object {
        const val CONTEXT = "service-discovery"
    }
}
