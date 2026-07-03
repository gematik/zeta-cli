package de.gematik.zeta.stress.db

/** A single SMC-B card: DER-encoded X.509 AUT certificate + its PKCS#8 EC private key. */
data class Card(val cardId: String, val cert: ByteArray, val privKey: ByteArray)

class CardStore(private val db: Db) {

    /** Insert [cards] in one transaction; existing `card_id`s are left untouched. */
    fun insertAll(cards: List<Card>) = db.transaction { c ->
        c.prepareStatement("INSERT OR IGNORE INTO card(card_id, cert, priv_key) VALUES (?, ?, ?)").use { ps ->
            for (card in cards) {
                ps.setString(1, card.cardId)
                ps.setBytes(2, card.cert)
                ps.setBytes(3, card.privKey)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    fun count(): Long = db.withConnection { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM card").use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    fun get(cardId: String): Card? = db.withConnection { c ->
        c.prepareStatement("SELECT cert, priv_key FROM card WHERE card_id = ?").use { ps ->
            ps.setString(1, cardId)
            ps.executeQuery().use { rs ->
                if (rs.next()) Card(cardId, rs.getBytes(1), rs.getBytes(2)) else null
            }
        }
    }

    /** The first [limit] card ids in ascending order — the sampling pool for preflight. */
    fun ids(limit: Int, offset: Int = 0): List<String> = db.withConnection { c ->
        c.prepareStatement("SELECT card_id FROM card ORDER BY card_id LIMIT ? OFFSET ?").use { ps ->
            ps.setInt(1, limit)
            ps.setInt(2, offset)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
    }
}
