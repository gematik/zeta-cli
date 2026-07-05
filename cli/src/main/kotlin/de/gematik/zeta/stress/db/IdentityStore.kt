package de.gematik.zeta.stress.db

/**
 * A single SMC-B identity, keyed by its gematik Telematik-ID (read from the AUT cert's Admission
 * extension). The Telematik-ID is also the value a PoPP token carries as `actorId`, so it doubles
 * as the join key for tokens and registered clients — there is no separate card id.
 */
data class Identity(val telematikId: String, val cert: ByteArray, val privKey: ByteArray)

class IdentityStore(private val db: Db) {

    /** Insert [identities] in one transaction; existing Telematik-IDs are left untouched. */
    fun insertAll(identities: List<Identity>) = db.transaction { c ->
        c.prepareStatement("INSERT OR IGNORE INTO identity(telematik_id, cert, priv_key) VALUES (?, ?, ?)").use { ps ->
            for (identity in identities) {
                ps.setString(1, identity.telematikId)
                ps.setBytes(2, identity.cert)
                ps.setBytes(3, identity.privKey)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    fun count(): Long = db.withConnection { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM identity").use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    /** Look up an identity by its Telematik-ID (the fleet signer + the CLI's `db` auth backend). */
    fun get(telematikId: String): Identity? = db.withConnection { c ->
        c.prepareStatement("SELECT cert, priv_key FROM identity WHERE telematik_id = ?").use { ps ->
            ps.setString(1, telematikId)
            ps.executeQuery().use { rs ->
                if (rs.next()) Identity(telematikId, rs.getBytes(1), rs.getBytes(2)) else null
            }
        }
    }

    /** The first [limit] Telematik-IDs in ascending order — the sampling pool for preflight. */
    fun ids(limit: Int, offset: Int = 0): List<String> = db.withConnection { c ->
        c.prepareStatement("SELECT telematik_id FROM identity ORDER BY telematik_id LIMIT ? OFFSET ?").use { ps ->
            ps.setInt(1, limit)
            ps.setInt(2, offset)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
    }
}
