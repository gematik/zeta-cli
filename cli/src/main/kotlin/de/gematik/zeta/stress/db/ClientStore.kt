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

/** A registered virtual client: one DCR registration under [telematikId], with its own state namespace. */
data class ClientRow(
    val clientRef: String,
    val telematikId: String,
    val resource: String,
    val scopes: List<String>,
    val createdAt: Long,
    val status: String,
)

/** Per-endpoint roster counts, for `zeta stress db info`. */
data class ResourceStat(val resource: String, val clients: Long, val identities: Long)

class ClientStore(private val db: Db) {

    fun insert(row: ClientRow) = db.withConnection { c ->
        c.prepareStatement(
            "INSERT OR REPLACE INTO client(client_ref, telematik_id, resource, scopes, created_at, status) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, row.clientRef)
            ps.setString(2, row.telematikId)
            ps.setString(3, row.resource)
            ps.setString(4, row.scopes.joinToString(" "))
            ps.setLong(5, row.createdAt)
            ps.setString(6, row.status)
            ps.executeUpdate()
        }
    }

    fun updateStatus(clientRef: String, status: String) = db.withConnection { c ->
        c.prepareStatement("UPDATE client SET status = ? WHERE client_ref = ?").use { ps ->
            ps.setString(1, status)
            ps.setString(2, clientRef)
            ps.executeUpdate()
        }
    }

    /** Clients registered for [telematikId] at [resource] — the per-endpoint preflight top-up count. */
    fun countForIdentity(telematikId: String, resource: String): Int = db.withConnection { c ->
        c.prepareStatement("SELECT count(*) FROM client WHERE telematik_id = ? AND resource = ?").use { ps ->
            ps.setString(1, telematikId)
            ps.setString(2, resource)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    fun count(): Long = db.withConnection { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM client").use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    /** Clients grouped by resource, with the distinct-identity count behind each — for `db info`. */
    fun perResource(): List<ResourceStat> = db.withConnection { c ->
        c.prepareStatement(
            "SELECT resource, count(*), count(DISTINCT telematik_id) FROM client GROUP BY resource ORDER BY resource",
        ).use { ps ->
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(ResourceStat(rs.getString(1), rs.getLong(2), rs.getLong(3))) }
            }
        }
    }

    /** Distinct identities (Telematik-IDs) registered for a resource — the roster for `popp get`. */
    fun identitiesForResource(resource: String): List<String> = db.withConnection { c ->
        c.prepareStatement("SELECT DISTINCT telematik_id FROM client WHERE resource = ? ORDER BY telematik_id").use { ps ->
            ps.setString(1, resource)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    /** A cohort of registered clients for a resource, capped at [limit]. */
    fun cohort(resource: String, limit: Int): List<ClientRow> = select(resource, limit)

    /** Every registered client for a resource — the whole preflight'd population. */
    fun forResource(resource: String): List<ClientRow> = select(resource, null)

    private fun select(resource: String, limit: Int?): List<ClientRow> = db.withConnection { c ->
        val sql = "SELECT client_ref, telematik_id, scopes, created_at, status FROM client " +
            "WHERE resource = ? ORDER BY client_ref" + if (limit != null) " LIMIT ?" else ""
        c.prepareStatement(sql).use { ps ->
            ps.setString(1, resource)
            if (limit != null) ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            ClientRow(
                                clientRef = rs.getString(1),
                                telematikId = rs.getString(2),
                                resource = resource,
                                scopes = rs.getString(3).split(" ").filter { it.isNotBlank() },
                                createdAt = rs.getLong(4),
                                status = rs.getString(5),
                            ),
                        )
                    }
                }
            }
        }
    }
}
