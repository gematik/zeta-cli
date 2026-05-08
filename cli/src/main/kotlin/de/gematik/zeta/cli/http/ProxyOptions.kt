package de.gematik.zeta.cli.http

import com.github.ajalt.clikt.core.UsageError
import de.gematik.zeta.sdk.network.http.client.config.ProxyConfig
import de.gematik.zeta.sdk.network.http.client.config.ProxyType
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.http.Url
import okhttp3.Authenticator
import okhttp3.Credentials
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Parse the trio of `--proxy URL` / `--proxy-user` / `--proxy-password` into the SDK's
 * [ProxyConfig] — the same type both the CLI's own Ktor clients and the Zeta SDK builders
 * consume, so the rest of the pipeline doesn't care which option carried the credentials.
 *
 * Only HTTP forward proxies are supported (`http://` and `https://` schemes). Two
 * equivalent ways to give credentials:
 *   - embedded in the URL: `--proxy http://alice:s3cret@proxy.example.com:8080`
 *   - separate flags: `--proxy http://proxy.example.com:8080 --proxy-user alice --proxy-password s3cret`
 *
 * Mixing both is fine — the explicit flags win, which lets the URL live in `zeta.yaml`
 * (or `ZETA_PROXY`) without a password while the password comes from `${PROXY_PASSWORD}`
 * via env-var substitution.
 */
internal fun parseProxyConfig(
    url: String,
    userOverride: String?,
    passwordOverride: String?,
): ProxyConfig {
    val uri = try {
        URI(url)
    } catch (e: Exception) {
        throw UsageError("invalid --proxy URL '$url': ${e.message}")
    }

    val scheme = uri.scheme?.lowercase()
        ?: throw UsageError("--proxy URL '$url' must include a scheme (http:// or https://)")
    if (scheme != "http" && scheme != "https") {
        throw UsageError("unsupported --proxy scheme '$scheme'; only http and https are supported")
    }

    val host = uri.host?.takeIf { it.isNotBlank() }
        ?: throw UsageError("--proxy URL '$url' has no host")
    val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 8080

    val (urlUser, urlPassword) = parseUserInfo(uri.rawUserInfo)
    val username = userOverride?.takeIf { it.isNotEmpty() } ?: urlUser
    val password = passwordOverride?.takeIf { it.isNotEmpty() } ?: urlPassword

    if (password != null && username == null) {
        throw UsageError(
            "--proxy-password set without a username — provide --proxy-user or embed " +
                "user info in --proxy URL",
        )
    }

    return ProxyConfig(
        type = ProxyType.HTTP,
        host = host,
        port = port,
        username = username,
        password = password?.toCharArray(),
    )
}

private fun parseUserInfo(raw: String?): Pair<String?, String?> {
    if (raw.isNullOrEmpty()) return null to null
    val idx = raw.indexOf(':')
    return if (idx < 0) {
        URLDecoder.decode(raw, Charsets.UTF_8) to null
    } else {
        URLDecoder.decode(raw.substring(0, idx), Charsets.UTF_8) to
            URLDecoder.decode(raw.substring(idx + 1), Charsets.UTF_8)
    }
}

/**
 * Set [proxy] on the engine's `proxy` field — host/port only. User-info is intentionally
 * **not** embedded here: Ktor's OkHttp engine (the connector module's transport) drops
 * URL user-info when it constructs the `java.net.Proxy`, so any auth via the URL is
 * silently lost. Use [applyProxyAuthenticator] on OkHttp engines for CONNECT auth, or
 * rely on Ktor CIO's behaviour where it does forward URL user-info on CONNECT.
 *
 * Engine-agnostic: works for both the CLI's CIO client (`HttpClientFactory`) and the
 * connector module's OkHttp client (`dotkonOkHttpClient`).
 */
internal fun HttpClientEngineConfig.applyProxy(proxy: ProxyConfig) {
    val userInfo = if (this is OkHttpConfig) {
        // OkHttp drops user-info; auth is added separately via proxyAuthenticator.
        ""
    } else {
        proxy.username?.let { u ->
            val pwd = proxy.password?.concatToString().orEmpty()
            "${URLEncoder.encode(u, Charsets.UTF_8)}:${URLEncoder.encode(pwd, Charsets.UTF_8)}@"
        }.orEmpty()
    }
    this.proxy = ProxyBuilder.http(Url("http://$userInfo${proxy.host}:${proxy.port}"))
}

/**
 * Install an OkHttp `proxyAuthenticator` so the engine answers proxy `407 Proxy
 * Authentication Required` (including on the CONNECT tunnel for HTTPS upstreams) with
 * a `Proxy-Authorization: Basic …` header. This is the only mechanism that actually
 * authenticates the CONNECT request — pre-emptive headers on the upstream HTTPS request
 * sit inside the TLS tunnel and the proxy never sees them.
 *
 * No-op when [ProxyConfig.username] is null (proxy without credentials).
 */
internal fun OkHttpConfig.applyProxyAuthenticator(proxy: ProxyConfig) {
    val user = proxy.username ?: return
    val pwd = proxy.password?.concatToString().orEmpty()
    val credentials = Credentials.basic(user, pwd)
    config {
        proxyAuthenticator(
            Authenticator { _, response ->
                response.request.newBuilder()
                    .header("Proxy-Authorization", credentials)
                    .build()
            },
        )
    }
}
