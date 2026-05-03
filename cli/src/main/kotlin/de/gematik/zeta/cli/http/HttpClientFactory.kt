package de.gematik.zeta.cli.http

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.io.path.inputStream
import kotlin.time.Duration

private val log = KotlinLogging.logger {}

fun createHttpClient(
    connectTimeout: Duration,
    requestTimeout: Duration,
    insecure: Boolean = false,
    caCertFiles: List<Path> = emptyList(),
): HttpClient {
    if (insecure) {
        log.warn { "TLS certificate verification is DISABLED. Use only for testing." }
    }
    val customTrustManager: X509TrustManager? = when {
        insecure -> InsecureTrustManager
        caCertFiles.isNotEmpty() -> buildTrustManager(caCertFiles)
        else -> null
    }
    return HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = connectTimeout.inWholeMilliseconds
            requestTimeoutMillis = requestTimeout.inWholeMilliseconds
        }
        installCurlieLogging()
        expectSuccess = false
        engine {
            if (customTrustManager != null) {
                https {
                    trustManager = customTrustManager
                }
            }
        }
    }
}

private object InsecureTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun buildTrustManager(caCertFiles: List<Path>): X509TrustManager {
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }

    // Seed with default JVM trust roots so user-supplied CAs are additive, not replacing.
    val defaultTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .also { it.init(null as KeyStore?) }
    defaultTmf.trustManagers.filterIsInstance<X509TrustManager>().first().acceptedIssuers
        .forEachIndexed { idx, cert -> keyStore.setCertificateEntry("default-$idx", cert) }

    val cf = CertificateFactory.getInstance("X.509")
    caCertFiles.forEachIndexed { fileIdx, path ->
        log.debug { "Loading CA certificates from $path" }
        path.inputStream().use { stream ->
            cf.generateCertificates(stream).forEachIndexed { certIdx, cert ->
                keyStore.setCertificateEntry("custom-$fileIdx-$certIdx", cert)
            }
        }
    }

    return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .also { it.init(keyStore) }
        .trustManagers
        .filterIsInstance<X509TrustManager>()
        .first()
}
