package de.gematik.zeta.cli.client

import de.gematik.zeta.cli.connector.ConnectorTokenProvider
import de.gematik.zeta.cli.connector.ConnectorSession
import de.gematik.zeta.sdk.authentication.SubjectTokenProvider
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.authentication.smcb.SmcbTokenProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path

private val log = KotlinLogging.logger {}

/**
 * Build a lazy SMC-B [SubjectTokenProvider] backed by the Connector in [session]. Nothing
 * touches the Connector until the SDK actually invokes `createSubjectToken` — when the
 * SDK has cached tokens it skips that call entirely and we save the SDS load + the SMC-B
 * enumeration round trips.
 *
 * The factory passed to [LazySubjectTokenProvider] is the work that used to happen
 * eagerly: lazy-connect the Connector, resolve the card handle, build [SmcbTokenProvider].
 */
internal fun buildConnectorTokenProvider(
    session: ConnectorSession,
    cardHandle: String?,
    iccsn: String?,
    telematikId: String?,
): SubjectTokenProvider = LazySubjectTokenProvider {
    val connector = session.connector()
    val resolvedHandle = resolveSmcbCardHandle(
        connector = connector,
        cardHandle = cardHandle,
        iccsn = iccsn,
        telematikId = telematikId,
    )
    log.info { "Using SMC-B card handle: $resolvedHandle" }
    val dotkon = session.dotkon
    SmcbTokenProvider(
        SmcbTokenProvider.ConnectorConfig(
            baseUrl = dotkon.url,
            mandantId = dotkon.mandantId,
            clientSystemId = dotkon.clientSystemId,
            workspaceId = dotkon.workplaceId,
            userId = dotkon.userId.orEmpty(),
            cardHandle = resolvedHandle,
        ),
        connectorApi = ConnectorTokenProvider(connector),
    )
}

/** Build an SMC-B [SubjectTokenProvider] backed by a PKCS#12 keystore on disk. */
internal fun buildP12TokenProvider(
    file: Path,
    alias: String,
    password: String,
): SubjectTokenProvider {
    log.info { "Using p12 fallback: file=$file alias=$alias" }
    return SmbTokenProvider(
        SmbTokenProvider.Credentials(
            keystoreFile = file.toString(),
            alias = alias,
            password = password,
        ),
    )
}
