package de.gematik.connector.engine.okhttp

import de.gematik.connector.Dotkon
import de.gematik.connector.dotkonAuth
import de.gematik.connector.toKeyManagers
import de.gematik.connector.toTrustManager
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

/**
 * Build a Ktor [HttpClient] backed by the OkHttp engine, wired for [dotkon].
 *
 * The koap-go `NewHTTPClient` analogue: TLS trust + SNI from `trustStore` /
 * `expectedHost`, optional client-cert auth (PKCS#12) or HTTP basic auth, and any extra
 * [configure] block. The caller owns the returned client — close it when done.
 *
 * The SOAP-level code (`ConnectorClient`) does not call this: any pre-built
 * [HttpClient] (any engine) works. This helper is offered for the common .kon-driven
 * case. Pull `io.ktor:ktor-client-okhttp` to use it.
 *
 * **Why OkHttp specifically:** Ktor CIO's TLS stack hard-codes RSA / DSS only and
 * silently drops EC client certs (see `CertificateType.kt` in CIO sources), which
 * breaks mutual TLS against any Connector that uses brainpool ECC card certs (KSP /
 * HBA / SMC-B). OkHttp routes through JSSE and presents whichever cert is installed.
 *
 * **SNI caveat:** OkHttp derives the SNI server name from the request URL's host. The
 * `expectedHost` field of [Dotkon] is wired into hostname *verification* here, but
 * cannot retarget the SNI hint without a custom socket factory. If you need SNI to
 * differ from the URL host, you'd need a different engine (Apache, or a custom
 * `SSLSocketFactory` that overrides `createSocket`).
 */
fun dotkonOkHttpClient(
    dotkon: Dotkon,
    configure: HttpClientConfig<OkHttpConfig>.() -> Unit = {},
): HttpClient = HttpClient(OkHttp) {
    engine { dotkonTls(dotkon) }
    dotkonAuth(dotkon)
    configure()
}

/**
 * Apply [dotkon]'s TLS settings to an [OkHttpConfig]: trust manager + client cert
 * via an [SSLContext]-derived `SSLSocketFactory`, and a `HostnameVerifier` for
 * `insecureSkipVerify` / `expectedHost`.
 */
fun OkHttpConfig.dotkonTls(dotkon: Dotkon) {
    val trust = dotkon.toTrustManager()
    val keys = dotkon.toKeyManagers()
    val needsCustomSocketFactory = trust != null || keys.isNotEmpty()

    config {
        if (needsCustomSocketFactory) {
            val tm = trust ?: defaultTrustManager()
            val ctx = SSLContext.getInstance("TLS").apply {
                init(keys.toTypedArray(), arrayOf(tm), null)
            }
            sslSocketFactory(ctx.socketFactory, tm)
        }

        when {
            dotkon.insecureSkipVerify -> hostnameVerifier(HostnameVerifier { _, _ -> true })
            !dotkon.expectedHost.isNullOrBlank() -> {
                val expected = dotkon.expectedHost
                val default = HttpsURLConnection.getDefaultHostnameVerifier()
                hostnameVerifier(HostnameVerifier { _, session -> default.verify(expected, session) })
            }
        }
    }
}

private fun defaultTrustManager(): javax.net.ssl.X509TrustManager {
    val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
        javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm(),
    )
    tmf.init(null as java.security.KeyStore?)
    return tmf.trustManagers.first { it is javax.net.ssl.X509TrustManager } as javax.net.ssl.X509TrustManager
}
