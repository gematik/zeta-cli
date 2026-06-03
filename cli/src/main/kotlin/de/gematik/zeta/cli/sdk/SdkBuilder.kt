package de.gematik.zeta.cli.sdk

import de.gematik.zeta.cli.CliConfig
import de.gematik.zeta.cli.client.applyCliHttpDefaults
import de.gematik.zeta.cli.storage.JsonFileStorage
import de.gematik.zeta.cli.trace.Tracer
import de.gematik.zeta.sdk.BuildConfig
import de.gematik.zeta.sdk.TpmConfig
import de.gematik.zeta.sdk.ZetaSdk
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.attestation.model.AttestationConfig
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.SubjectTokenProvider
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.storage.StorageConfig
import de.gematik.zeta.sdk.tpm.TpmProvider
import java.nio.file.Path

const val OID_ZETA_GUARD = "1.2.276.0.76.4.328"

/**
 * Build a [ZetaSdkClient] with the common CLI-wide defaults: product id, in-process
 * storage, software attestation, ZETA Guard role OID. Caller supplies the resource origin,
 * scopes, on-disk storage location, and a [SubjectTokenProvider].
 *
 * Used by both auth-requiring commands (real Connector/PKCS#12 providers, via
 * [de.gematik.zeta.cli.client.ZetaSessionCommand]) and read-only commands (the
 * [NoopSubjectTokenProvider] stub, via [withReadOnlySdk]). Centralising the build keeps the
 * two paths byte-identical except for the provider — which is the whole point.
 */
internal fun buildZetaSdkClient(
    resource: String,
    scopes: List<String>,
    storagePath: Path,
    tokenProvider: SubjectTokenProvider,
    cliConfig: CliConfig,
): ZetaSdkClient =
    Tracer.span(
        "sdk.init",
        attrs = mapOf("resource" to resource, "scopes" to scopes.joinToString(",")),
    ) {
        ZetaSdk.build(
            resource,
            BuildConfig(
                productId = "ZETA-Test-Client",
                productVersion = "1.0.0",
                clientName = "zeta-cli",
                storageConfig = StorageConfig.Custom(JsonFileStorage(storagePath)),
                tpmConfig = object : TpmConfig {},
                authConfig =
                    AuthConfig(
                        scopes = scopes,
                        exp = 30,
                        aslProdEnvironment = cliConfig.aslProdEnvironment,
                        subjectTokenProvider = tokenProvider,
                        attestation = AttestationConfig.software(),
                        requiredRoleOid = OID_ZETA_GUARD,
                    ),
                platformProductId = currentPlatformProductId(),
                httpClientBuilder = ZetaHttpClientBuilder().applyCliHttpDefaults(cliConfig),
            ),
        )
    }

/**
 * Pick the [PlatformProductId] variant matching the host OS. Software-attestation flows
 * don't validate the per-platform fields (storeId, applicationId, etc.) — but the auth
 * server **does** check that `platform_product_id.platform` matches the top-level
 * `client_statement.platform` claim (which the SDK derives from `os.name`). Sending
 * `apple`/`macos` on Windows trips a `"Invalid combination of SOFTWARE and APPLE"` reject
 * at the token endpoint. Per-platform fields are intentionally left as placeholders here;
 * fill them in if the auth server starts validating them.
 */
private fun currentPlatformProductId(): PlatformProductId {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        "windows" in osName -> {
            PlatformProductId.WindowsProductId(
                PlatformProductId.PLATFORM_WINDOWS,
                // storeId =
                "",
                // packageFamilyName =
                "",
            )
        }

        "linux" in osName -> {
            PlatformProductId.LinuxProductId(
                PlatformProductId.PLATFORM_LINUX,
                // packagingType =
                "",
                // applicationId =
                "de.gematik.zeta.cli",
                // version =
                "1.0.0",
            )
        }

        else -> {
            PlatformProductId.AppleProductId(
                PlatformProductId.PLATFORM_APPLE,
                // platformType =
                "macos",
                // appBundleIds =
                listOf(),
            )
        }
    }
}

/**
 * Stand-in [SubjectTokenProvider] for SDK operations that never trigger the auth flow:
 * `status()`, `discover()`, `ZetaSdk.forget(client)`. These read or wipe state but never
 * call `createSubjectToken` — the SDK only does that when it needs a fresh subject token to
 * exchange at the token endpoint. Wiring a stub lets read-only commands build an SDK
 * without forcing the user to provide Connector / PKCS#12 options first.
 *
 * If the SDK ever does call this (regression or misuse), we want a loud failure rather
 * than a silent hang or an empty token — hence the [error].
 */
internal object NoopSubjectTokenProvider : SubjectTokenProvider {
    override suspend fun createSubjectToken(
        clientId: String,
        dpopKey: String,
        nonceBytes: ByteArray,
        audience: String,
        now: Long,
        expiration: Long,
        tpmProvider: TpmProvider,
    ): String =
        error(
            "Subject token requested by a read-only SDK operation — this command was built with " +
                "NoopSubjectTokenProvider on the assumption no token would be needed. Likely a bug.",
        )
}
