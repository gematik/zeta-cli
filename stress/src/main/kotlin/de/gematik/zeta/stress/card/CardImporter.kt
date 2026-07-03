package de.gematik.zeta.stress.card

import de.gematik.zeta.stress.db.Card
import de.gematik.zeta.stress.db.CardStore
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

private val log = KotlinLogging.logger {}

/**
 * Imports the SMC-B test corpus into the `card` table. The source is a directory of `*.tar.gz`
 * bundles, each holding 1000 cards as DER triples named `<cardId>-C_SMCB91_AUT_E256_X509.{crt,prv,pub}`.
 * We keep `.crt` (X.509 cert) and `.prv` (PKCS#8 EC key); `.pub` is ignored.
 */
class CardImporter(private val store: CardStore) {

    fun importDir(dir: Path): Long {
        require(dir.isDirectory()) { "$dir is not a directory" }
        val tarballs = dir.listDirectoryEntries("*.tar.gz").sortedBy { it.name }
        require(tarballs.isNotEmpty()) { "no *.tar.gz bundles found in $dir" }
        log.info { "Importing ${tarballs.size} bundles from $dir" }

        var imported = 0L
        for (tarball in tarballs) {
            val cards = readBundle(tarball)
            store.insertAll(cards)
            imported += cards.size
            log.info { "  ${tarball.name}: ${cards.size} cards (total $imported)" }
        }
        return imported
    }

    private fun readBundle(tarball: Path): List<Card> {
        val certs = HashMap<String, ByteArray>()
        val keys = HashMap<String, ByteArray>()
        TarArchiveInputStream(GzipCompressorInputStream(tarball.toFile().inputStream().buffered())).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.substringAfterLast('/')
                    val cardId = name.substringBefore('-')
                    when {
                        name.endsWith(".crt") -> certs[cardId] = tar.readBytes()
                        name.endsWith(".prv") -> keys[cardId] = tar.readBytes()
                    }
                }
                entry = tar.nextEntry
            }
        }
        return certs.keys.mapNotNull { id ->
            val cert = certs[id] ?: return@mapNotNull null
            val key = keys[id] ?: return@mapNotNull null
            Card(id, cert, key)
        }
    }
}
