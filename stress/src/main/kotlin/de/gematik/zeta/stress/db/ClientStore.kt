package de.gematik.zeta.stress.db

/** A registered virtual client: one DCR registration under [cardId], with its own state namespace. */
data class ClientRow(
    val clientRef: String,
    val cardId: String,
    val resource: String,
    val scopes: List<String>,
    val createdAt: Long,
    val status: String,
)

class ClientStore(private val db: Db) {

    fun insert(row: ClientRow) = db.withConnection { c ->
        c.prepareStatement(
            "INSERT OR REPLACE INTO client(client_ref, card_id, resource, scopes, created_at, status) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, row.clientRef)
            ps.setString(2, row.cardId)
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

    fun countForCard(cardId: String): Int = db.withConnection { c ->
        c.prepareStatement("SELECT count(*) FROM client WHERE card_id = ?").use { ps ->
            ps.setString(1, cardId)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    fun count(): Long = db.withConnection { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM client").use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    /** A cohort of registered clients for a resource, capped at [limit]. */
    fun cohort(resource: String, limit: Int): List<ClientRow> = db.withConnection { c ->
        c.prepareStatement(
            "SELECT client_ref, card_id, scopes, created_at, status FROM client " +
                "WHERE resource = ? ORDER BY client_ref LIMIT ?",
        ).use { ps ->
            ps.setString(1, resource)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            ClientRow(
                                clientRef = rs.getString(1),
                                cardId = rs.getString(2),
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
