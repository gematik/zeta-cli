package de.gematik.zeta.cli.http

import de.gematik.zeta.sdk.network.http.client.config.ProxyConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.nio.file.Path
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.io.path.inputStream
import kotlin.time.Duration

private val log = KotlinLogging.logger {}

/**
 * Ktor [HttpClient] for the CLI's own (non-SDK) calls — `zeta inspect`, `zeta connector inspect`,
 * `zeta connector get cards`.
 *
 * Engine choice: **OkHttp**. We were on Ktor CIO, but CIO's CONNECT-tunnel implementation
 * doesn't send `Proxy-Authorization` preemptively and drops the connection on a 407 reply,
 * which presents as `SocketException: Connection reset` against any authenticating HTTP
 * forward proxy (corporate proxies fronting HTTPS upstreams). OkHttp's `proxyAuthenticator`
 * answers the 407 correctly — same engine the connector module already uses for its mTLS
 * traffic, so we now have one proxy story across the CLI.
 */
fun createHttpClient(
    connectTimeout: Duration,
    requestTimeout: Duration,
    insecure: Boolean = false,
    caCertFiles: List<Path> = emptyList(),
    proxy: ProxyConfig? = null,
): HttpClient {
    if (insecure) {
        log.warn { "TLS certificate verification is DISABLED. Use only for testing." }
    }
    proxy?.let {
        log.info {
            val auth = if (it.username != null) " (auth: ${it.username})" else ""
            "HTTP proxy: ${it.host}:${it.port}$auth"
        }
    }
    val customTrustManager: X509TrustManager? = when {
        insecure -> InsecureTrustManager
        caCertFiles.isNotEmpty() -> buildTrustManager(caCertFiles)
        else -> null
    }
    return HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = connectTimeout.inWholeMilliseconds
            requestTimeoutMillis = requestTimeout.inWholeMilliseconds
        }
        installCurlieLogging()
        expectSuccess = false
        engine {
            // OkHttp wants the SSLSocketFactory built from the same trust manager. The
            // factory ultimately lives in JSSE — same code path the SDK takes in
            // ZetaHttpClient.jvm.kt::buildInsecureTls, so behaviour is consistent.
            customTrustManager?.let { tm ->
                val ctx = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf(tm), SecureRandom())
                }
                config {
                    sslSocketFactory(ctx.socketFactory, tm)
                    if (insecure) hostnameVerifier { _, _ -> true }
                }
            }
            proxy?.let {
                applyProxy(it)
                applyProxyAuthenticator(it)
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
