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

    fun count(): Long = db.withConnection { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM popp_token").use { rs -> rs.next(); rs.getLong(1) }
        }
    }
}
