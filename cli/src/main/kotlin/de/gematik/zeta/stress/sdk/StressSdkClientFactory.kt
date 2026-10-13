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
package de.gematik.zeta.stress.sdk

import de.gematik.zeta.sdk.BuildConfig
import de.gematik.zeta.sdk.TpmConfig
import de.gematik.zeta.sdk.ZetaSdk
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.attestation.model.AttestationConfig
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.smcb.CustomSmcbTokenProvider
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.storage.StorageConfig
import de.gematik.zeta.stress.identity.DbCardSigner
import de.gematik.zeta.stress.db.Identity
import de.gematik.zeta.stress.db.Db
import de.gematik.zeta.stress.storage.SqliteSdkStorage

const val OID_ZETA_GUARD = "1.2.276.0.76.4.328"

/** HTTP + environment knobs shared by every virtual client the harness builds. */
data class HttpSettings(
    val connectTimeoutMs: Long? = null,
    val requestTimeoutMs: Long? = null,
    val insecure: Boolean = false,
    val caCertFiles: List<String> = emptyList(),
    val aslProdEnvironment: Boolean = false,
)

/**
 * Builds a [ZetaSdkClient] per virtual client: its own SQLite-backed [SqliteSdkStorage]
 * namespace, an in-software [SmbTokenProvider] over the identity's PKCS#12, software attestation,
 * and the ZETA Guard role OID. Mirrors the CLI's `buildZetaSdkClient` minus the Connector path.
 */
class StressSdkClientFactory(
    private val db: Db,
    private val http: HttpSettings,
) {
    fun build(clientRef: String, identity: Identity, resource: String, scopes: List<String>): ZetaSdkClient =
        ZetaSdk.build(
            resource,
            BuildConfig(
                productId = "ZETA-Stress-Client",
                productVersion = "1.0.0",
                clientName = "zeta-stress",
                storageConfig = StorageConfig.Custom(SqliteSdkStorage(clientRef, db)),
                tpmConfig = object : TpmConfig {},
                authConfig = AuthConfig(
                    scopes = scopes,
                    exp = 30,
                    aslProdEnvironment = http.aslProdEnvironment,
                    subjectTokenProvider = CustomSmcbTokenProvider(DbCardSigner(identity)),
                    attestation = AttestationConfig.software(),
                    requiredRoleOid = OID_ZETA_GUARD,
                ),
                platformProductId = currentPlatformProductId(),
                httpClientBuilder = buildHttpClientBuilder(),
            ),
        )

    private fun buildHttpClientBuilder(): ZetaHttpClientBuilder =
        ZetaHttpClientBuilder().apply { applyStressHttp(http) }
}

/** Apply the shared [HttpSettings] to a [ZetaHttpClientBuilder] — used at SDK build + `sdk.ws(builder=…)`. */
fun ZetaHttpClientBuilder.applyStressHttp(http: HttpSettings) {
    timeouts(http.connectTimeoutMs, http.requestTimeoutMs)
    if (http.insecure) disableServerValidation(true)
    http.caCertFiles.forEach { addCaPemFile(it) }
}

/**
 * The auth server checks that `platform_product_id.platform` matches the software statement's
 * `platform` claim (derived from `os.name`). Per-platform fields are placeholders — software
 * attestation doesn't validate them. Kept in sync with the CLI's `SdkBuilder.currentPlatformProductId`.
 */
private fun currentPlatformProductId(): PlatformProductId {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        "windows" in osName -> PlatformProductId.WindowsProductId(PlatformProductId.PLATFORM_WINDOWS, "", "")
        "linux" in osName -> PlatformProductId.LinuxProductId(
            PlatformProductId.PLATFORM_LINUX, "", "de.gematik.zeta.stress", "1.0.0",
        )
        else -> PlatformProductId.AppleProductId(PlatformProductId.PLATFORM_APPLE, "macos", listOf())
    }
}
