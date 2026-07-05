package de.gematik.zeta.stress.identity

import de.gematik.zeta.stress.db.Identity
import de.gematik.zeta.stress.db.IdentityStore
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

private val log = KotlinLogging.logger {}

/**
 * Imports the SMC-B test corpus into the `identity` table. The source is a directory of `*.tar.gz`
 * bundles, each holding 1000 identities as DER triples named `<iccsn>-C_SMCB91_AUT_E256_X509.{crt,prv,pub}`.
 * We keep `.crt` (X.509 cert) and `.prv` (PKCS#8 EC key); `.pub` is ignored.
 */
class IdentityImporter(private val store: IdentityStore) {

    /** [onProgress] is called after each bundle with (bundles done, bundles total, identities so far). */
    fun importDir(dir: Path, onProgress: (Int, Int, Long) -> Unit = { _, _, _ -> }): Long {
        require(dir.isDirectory()) { "$dir is not a directory" }
        val tarballs = dir.listDirectoryEntries("*.tar.gz").sortedBy { it.name }
        require(tarballs.isNotEmpty()) { "no *.tar.gz bundles found in $dir" }
        log.info { "Importing ${tarballs.size} bundles from $dir" }

        var imported = 0L
        for ((i, tarball) in tarballs.withIndex()) {
            val identities = readBundle(tarball)
            store.insertAll(identities)
            imported += identities.size
            log.info { "  ${tarball.name}: ${identities.size} identities (total $imported)" }
            onProgress(i + 1, tarballs.size, imported)
        }
        return imported
    }

    private fun readBundle(tarball: Path): List<Identity> {
        val certs = HashMap<String, ByteArray>()
        val keys = HashMap<String, ByteArray>()
        TarArchiveInputStream(GzipCompressorInputStream(tarball.toFile().inputStream().buffered())).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.substringAfterLast('/')
                    val iccsn = name.substringBefore('-')
                    when {
                        name.endsWith(".crt") -> certs[iccsn] = tar.readBytes()
                        name.endsWith(".prv") -> keys[iccsn] = tar.readBytes()
                    }
                }
                entry = tar.nextEntry
            }
        }
        return certs.keys.mapNotNull { stem ->
            val cert = certs[stem] ?: return@mapNotNull null
            val key = keys[stem] ?: return@mapNotNull null
            val telematikId = telematikIdOf(cert) ?: run {
                log.warn { "skipping $stem: no Telematik-ID in cert" }
                return@mapNotNull null
            }
            Identity(telematikId, cert, key)
        }
    }
}
