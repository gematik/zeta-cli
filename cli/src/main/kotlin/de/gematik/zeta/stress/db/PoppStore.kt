package de.gematik.zeta.stress.db

/**
 * One imported PoPP token, indexed by the three keys carried in its claims: the acting identity
 * ([actorId], which is a Telematik-ID), the insurant ([patientId]), and the insurance ([insurerId]).
 * [telematikId] is the [actorId] resolved against the corpus — null when no imported identity matched.
 */
data class PoppRow(
    val telematikId: String?,
    val actorId: String,
    val patientId: String,
    val insurerId: String,
    val proofTime: Long?,
    val iat: Long?,
    val kid: String?,
    val token: String,
)

/** The long-lived PoPP-token cache — populated once by `import-popp`, read at run time. */
class PoppStore(private val db: Db) {

    fun insertAll(rows: List<PoppRow>) = db.transaction { c ->
        c.prepareStatement(
            "INSERT OR REPLACE INTO popp_token(telematik_id, actor_id, patient_id, insurer_id, proof_time, iat, kid, token) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { ps ->
            for (r in rows) {
                ps.setString(1, r.telematikId)
                ps.setString(2, r.actorId)
                ps.setString(3, r.patientId)
                ps.setString(4, r.insurerId)
                if (r.proofTime != null) ps.setLong(5, r.proofTime) else ps.setNull(5, java.sql.Types.INTEGER)
                if (r.iat != null) ps.setLong(6, r.iat) else ps.setNull(6, java.sql.Types.INTEGER)
                ps.setString(7, r.kid)
                ps.setString(8, r.token)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    /** How many tokens are bound to [telematikId] — the per-identity top-up count for `popp get`. */
    fun countForIdentity(telematikId: String): Int = db.withConnection { c ->
        c.prepareStatement("SELECT count(*) FROM popp_token WHERE telematik_id = ?").use { ps ->
            ps.setString(1, telematikId)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    /** Every stored token as a full row — for `popp export`. */
    fun allRows(): List<PoppRow> = db.withConnection { c ->
        c.createStatement().use { st ->
            st.executeQuery(
                "SELECT telematik_id, actor_id, patient_id, insurer_id, proof_time, iat, kid, token FROM popp_token",
            ).use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            PoppRow(
                                telematikId = rs.getString(1),
                                actorId = rs.getString(2),
                                patientId = rs.getString(3),
                                insurerId = rs.getString(4),
                                proofTime = rs.getLong(5).takeUnless { rs.wasNull() },
                                iat = rs.getLong(6).takeUnless { rs.wasNull() },
                                kid = rs.getString(7),
                                token = rs.getString(8),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Tokens bound to [telematikId], newest patient-proof first, for round-robin at run time. */
    fun forIdentity(telematikId: String): List<String> = db.withConnection { c ->
        c.prepareStatement("SELECT token FROM popp_token WHERE telematik_id = ? ORDER BY proof_time DESC").use { ps ->
            ps.setString(1, telematikId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    /** Every token, as a global pool fallback for clients whose identity has none bound. */
    fun any(): List<String> = db.withConnection { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT token FROM popp_token").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    fun count(): Long = db.withConnection { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM popp_token").use { rs -> rs.next(); rs.getLong(1) }
        }
    }
}
