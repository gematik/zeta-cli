package de.gematik.connector

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Build the [X509TrustManager] implied by [dotkon].
 *
 * - `insecureSkipVerify = true` → accepts any chain.
 * - `trustStore` populated → custom manager pinning every cert in the store. End-entity
 *   certs are matched by exact equality (mirroring koap-go's `VerifyConnection`); CA
 *   certs anchor a standard chain build.
 * - Otherwise → `null` (caller should leave engine defaults / JVM trust store in place).
 */
fun Dotkon.toTrustManager(): X509TrustManager? =
    when {
        insecureSkipVerify -> AllAcceptingTrustManager
        trustStore.isNotEmpty() -> pinnedTrustManager(parseTrustedCertificates(trustedCertificateBytes()))
        else -> null
    }

/**
 * Build the [KeyManager]s implied by [dotkon] for client-cert auth. Returns an empty
 * list when no PKCS#12 credential is configured.
 */
fun Dotkon.toKeyManagers(): List<KeyManager> {
    val pkcs12 = credentials as? Credentials.Pkcs12 ?: return emptyList()
    val password = pkcs12.password.toCharArray()
    val ks =
        KeyStore.getInstance("PKCS12").apply {
            load(ByteArrayInputStream(pkcs12.decodedData()), password)
        }
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(ks, password)
    return kmf.keyManagers.toList()
}

/**
 * Convenience: an [SSLContext] initialised with [toTrustManager] + [toKeyManagers].
 *
 * Useful for engines that consume an `SSLSocketFactory` (Ktor OkHttp / Ktor Apache /
 * raw `HttpsURLConnection`). CIO uses a different model and bypasses this in its bridge.
 */
fun Dotkon.toSslContext(): SSLContext {
    val trust = toTrustManager() ?: defaultTrustManager()
    val keys = toKeyManagers()
    return SSLContext.getInstance("TLS").apply {
        init(keys.toTypedArray(), arrayOf(trust), null)
    }
}

// ---- internals shared across engines --------------------------------------------------

internal object AllAcceptingTrustManager : X509TrustManager {
    override fun checkClientTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) {}

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) {}

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun parseTrustedCertificates(bytes: List<ByteArray>): List<X509Certificate> {
    if (bytes.isEmpty()) return emptyList()
    val cf = CertificateFactory.getInstance("X.509")
    return bytes.mapIndexed { i, der ->
        try {
            cf.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        } catch (e: Exception) {
            throw IllegalArgumentException("trustStore[$i] is not a DER X.509 certificate", e)
        }
    }
}

/**
 * Trust manager that accepts a chain whose leaf appears in [trusted] (end-entity
 * pinning) OR chains up to a CA in [trusted]. EE certs cannot be JSSE trust anchors,
 * so we match them by exact equality.
 */
private fun pinnedTrustManager(trusted: List<X509Certificate>): X509TrustManager {
    val (cas, ees) = trusted.partition { it.basicConstraints >= 0 } // -1 = not a CA
    val caKeyStore =
        KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            cas.forEachIndexed { i, c -> setCertificateEntry("ca-$i", c) }
        }
    val caTrustManager = if (cas.isNotEmpty()) defaultTrustManager(caKeyStore) else null

    return object : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
        ): Unit = throw UnsupportedOperationException("client trust is irrelevant for an HTTP client")

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
        ) {
            if (chain.isEmpty()) throw CertificateException("empty chain")
            val leaf = chain[0]
            if (ees.any { it == leaf }) return
            if (caTrustManager != null) {
                caTrustManager.checkServerTrusted(chain, authType)
                return
            }
            throw CertificateException("certificate not trusted: ${leaf.subjectX500Principal}")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = (caTrustManager?.acceptedIssuers ?: emptyArray())
    }
}

private fun defaultTrustManager(ks: KeyStore? = null): X509TrustManager {
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(ks)
    return tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
}
