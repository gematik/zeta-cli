package de.gematik.connector

import de.gematik.connector.api.gematik.conn.authsignatureservice74.ExternalAuthenticateEnvelope
import de.gematik.connector.api.gematik.conn.authsignatureservice74.ExternalAuthenticateResponseEnvelope
import de.gematik.connector.api.gematik.conn.cardservice821.Operations as CardServiceOperations
import de.gematik.connector.api.gematik.conn.cardservice81.Card
import de.gematik.connector.api.gematik.conn.cardservice821.SecureSendAPDU
import de.gematik.connector.api.gematik.conn.cardservice821.SecureSendAPDUEnvelope
import de.gematik.connector.api.gematik.conn.cardservice821.SecureSendAPDUResponseEnvelope
import de.gematik.connector.api.gematik.conn.cardservice821.StartCardSession
import de.gematik.connector.api.gematik.conn.cardservice821.StartCardSessionEnvelope
import de.gematik.connector.api.gematik.conn.cardservice821.StartCardSessionResponseEnvelope
import de.gematik.connector.api.gematik.conn.cardservice821.StopCardSession
import de.gematik.connector.api.gematik.conn.cardservice821.StopCardSessionEnvelope
import de.gematik.connector.api.gematik.conn.cardservice821.StopCardSessionResponseEnvelope
import de.gematik.connector.api.gematik.conn.cardservicecommon20.CardType
import de.gematik.connector.api.gematik.conn.certificateservice602.CryptType
import de.gematik.connector.api.gematik.conn.certificateservice602.ReadCardCertificate
import de.gematik.connector.api.gematik.conn.certificateservice602.ReadCardCertificateCertRefList
import de.gematik.connector.api.gematik.conn.certificateservice602.ReadCardCertificateEnvelope
import de.gematik.connector.api.gematik.conn.certificateservice602.ReadCardCertificateResponseEnvelope
import de.gematik.connector.api.gematik.conn.connectorcontext20.Context
import de.gematik.connector.api.gematik.conn.eventservice72.GetCards
import de.gematik.connector.api.gematik.conn.eventservice72.GetCardsEnvelope
import de.gematik.connector.api.gematik.conn.eventservice72.GetCardsResponseEnvelope
import de.gematik.connector.api.gematik.conn.eventservice72.GetResourceInformation
import de.gematik.connector.api.gematik.conn.eventservice72.GetResourceInformationEnvelope
import de.gematik.connector.api.gematik.conn.eventservice72.GetResourceInformationResponse
import de.gematik.connector.api.gematik.conn.eventservice72.GetResourceInformationResponseEnvelope
import de.gematik.connector.api.gematik.conn.eventservice72.Operations
import de.gematik.connector.api.gematik.conn.signatureservice74.BinaryString
import de.gematik.connector.api.gematik.conn.signatureservice74.ExternalAuthenticate
import de.gematik.connector.api.gematik.conn.signatureservice74.ExternalAuthenticateOptionalInputs
import de.gematik.connector.api.oasis.dss10core.Base64Data
import de.gematik.connector.crypto.parseX509
import de.gematik.connector.crypto.telematikId
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import java.security.cert.X509Certificate
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.measureTimedValue
import de.gematik.connector.api.gematik.conn.authsignatureservice741.Operations as AuthSignatureOperations
import de.gematik.connector.api.gematik.conn.certificateservice602.Operations as CertificateServiceOperations

private val log = KotlinLogging.logger {}

/**
 * Standard signature-type URI for ECDSA on SMC-B / HBA cards (BSI TR-03111).
 * Required by the Connector's `ExternalAuthenticate` to pick the ECC card key.
 */
private const val ECDSA_SIGNATURE_TYPE = "urn:bsi:tr:03111:ecdsa"

/**
 * CardService version pin for `SecureSendAPDU` + `StartCardSession` / `StopCardSession`.
 * The operations were introduced in v8.2.0, but v8.2.1 reshaped `SecureSendAPDU` to take a
 * single `SignedScenario` (dropping `TransactionData` / `SignatureObject` / `X509Certificate`)
 * and that's the wire format real Connectors validate against. Our generated DTOs are
 * bound to the v8.2.1 namespace; targeting a Connector that only advertises older versions
 * surfaces a clear [ServiceNotFoundException] from [ConnectorClient.serviceProxy].
 */
private const val CARD_SERVICE_V821 = "8.2.1"

/**
 * Client-side context (`<ContextType>` in WSDL parlance) — identifies the calling
 * mandant, workplace, client system and (optionally) user against the Connector.
 *
 * Constructible from a [Dotkon] via [Dotkon.toConnectorContext].
 */
data class ConnectorContext(
    val mandantId: String,
    val clientSystemId: String,
    val workplaceId: String,
    val userId: String? = null,
) {
    /** Build the SOAP-side [Context] sent on every service call. */
    fun toApiContext(): Context =
        Context(
            mandantId = mandantId,
            clientSystemId = clientSystemId,
            workplaceId = workplaceId,
            userId = userId,
        )
}

fun Dotkon.toConnectorContext(): ConnectorContext =
    ConnectorContext(
        mandantId = mandantId,
        clientSystemId = clientSystemId,
        workplaceId = workplaceId,
        userId = userId,
    )

/**
 * High-level Connector client.
 *
 * The HTTP client is **provided by the caller** — the lib does not own its lifecycle,
 * does not know which engine it uses, and never closes it. Use
 * [de.gematik.connector.engine.okhttp.dotkonOkHttpClient] for the common .kon-driven
 * case, or build your own. Callers must close the [HttpClient] when they're done.
 *
 * Construct via [connect] (which loads the SDS) or directly when SDS contents are
 * already in hand (e.g. cached or test-injected).
 */
class ConnectorClient(
    val httpClient: HttpClient,
    val context: ConnectorContext,
    val services: ConnectorServices,
) {
    /** [ServiceProxy] for the highest-semver version of [serviceName]. */
    fun latestServiceProxy(serviceName: String): ServiceProxy {
        val (svc, ver) =
            services.findLatest(serviceName)
                ?: throw ServiceNotFoundException("service not found: $serviceName")
        log.debug { "Selected $serviceName ${ver.version} -> ${ver.endpointTLS?.location ?: "<no endpoint>"}" }
        return ServiceProxy(
            httpClient = httpClient,
            endpoint = ver.endpointTLS?.location.orEmpty(),
            service = svc,
            serviceVersion = ver,
        )
    }

    /**
     * [ServiceProxy] for [serviceName] at the highest version that's at least [minVersion].
     * Use this when you call into operations that were added in a specific WSDL revision
     * (e.g. CardService 8.2.0's `SecureSendAPDU` / `StartCardSession` / `StopCardSession`)
     * and don't want a silent namespace mismatch when the Connector only advertises older
     * versions.
     *
     * Two kinds of failure get distinct messages so the operator knows whether their
     * Connector is wrong or just stale:
     *   - service entirely absent from the SDS;
     *   - service present but no version satisfies [minVersion] (the message lists the
     *     versions the Connector *did* advertise).
     */
    fun serviceProxy(serviceName: String, minVersion: String): ServiceProxy {
        val service = services.serviceInformation.service
            .firstOrNull { it.name == serviceName }
            ?: throw ServiceNotFoundException(
                "Connector does not advertise $serviceName in its service directory",
            )
        val minNum = semverAsNumber(minVersion)
        val version = service.versions.version
            .filter { semverAsNumber(it.version) >= minNum }
            .maxByOrNull { semverAsNumber(it.version) }
            ?: throw ServiceNotFoundException(
                "Connector advertises $serviceName but no version ≥ $minVersion " +
                    "(available: ${service.versions.version.joinToString(", ") { it.version }})",
            )
        log.debug {
            "Selected $serviceName ${version.version} (required ≥ $minVersion) " +
                "-> ${version.endpointTLS?.location ?: "<no endpoint>"}"
        }
        return ServiceProxy(
            httpClient = httpClient,
            endpoint = version.endpointTLS?.location.orEmpty(),
            service = service,
            serviceVersion = version,
        )
    }

    // ----- convenience methods (mirror koap-go's Client) ------------------------------

    /** `EventService.GetCards` with no filter — returns every card the Connector sees. */
    suspend fun getAllCards(): List<Card> = getCardsByType(emptyList())

    /** `EventService.GetCards` filtered by [cardTypes]. Empty list = unfiltered. */
    suspend fun getCardsByType(cardTypes: List<CardType>): List<Card> {
        log.debug { "GetCards types=${cardTypes.ifEmpty { "<all>" }}" }
        val proxy = latestServiceProxy(ServiceNames.EventService)
        // Mimics koap-go: when no types are requested, do a single unfiltered call.
        val typesToCall: List<CardType?> = if (cardTypes.isEmpty()) listOf(null) else cardTypes
        return buildList {
            for (cardType in typesToCall) {
                val envelope =
                    GetCardsEnvelope(
                        body =
                            GetCardsEnvelope.Body(
                                getCards =
                                    GetCards(
                                        context = context.toApiContext(),
                                        cardType = cardType,
                                    ),
                            ),
                    )
                val resp: GetCardsResponseEnvelope =
                    proxy.call(Operations.GetCards, envelope)
                resp.requireSuccess("GetCards($cardType)")
                resp.body.getCardsResponse
                    ?.cards
                    ?.card
                    ?.let(::addAll)
            }
        }.also { log.debug { "GetCards returned ${it.size} card(s)" } }
    }

    /**
     * Read the C.AUT (authentication) ECC certificate from a card and return its DER bytes.
     *
     * Hardcoded to `C.AUT` + `ECC` because that's the only combination used by Zeta /
     * gematik card-based mTLS today. KSP / SMC-B / HBA / SMC-KT cards all carry an ECC
     * authentication cert; the RSA variants are legacy.
     *
     * Throws [SoapFaultException] if the Connector reports a fault (card missing, PIN
     * not verified, …) or [IllegalStateException] if the response is shaped unexpectedly.
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun readCardAutCertificate(cardHandle: String): ByteArray {
        log.debug { "ReadCardCertificate cardHandle=$cardHandle certRef=C.AUT crypt=ECC" }
        val proxy = latestServiceProxy(ServiceNames.CertificateService)
        val envelope =
            ReadCardCertificateEnvelope(
                body =
                    ReadCardCertificateEnvelope.Body(
                        readCardCertificate =
                            ReadCardCertificate(
                                cardHandle = cardHandle,
                                context = context.toApiContext(),
                                certRefList = ReadCardCertificateCertRefList(certRef = listOf("C.AUT")),
                                crypt = CryptType.Ecc,
                            ),
                    ),
            )
        val resp: ReadCardCertificateResponseEnvelope =
            proxy.call(CertificateServiceOperations.ReadCardCertificate, envelope)
        resp.requireSuccess("ReadCardCertificate(cardHandle=$cardHandle)")
        val info =
            resp.body.readCardCertificateResponse
                ?.x509DataInfoList
                ?.x509DataInfo
                ?.firstOrNull()
                ?: error("ReadCardCertificate: empty X509DataInfoList")
        val b64 =
            info.x509Data?.x509Certificate
                ?: error("ReadCardCertificate: no X509Certificate in response (certRef=${info.certRef})")
        return Base64.Mime
            .decode(b64)
            .also { log.debug { "ReadCardCertificate cardHandle=$cardHandle returned ${it.size}-byte cert" } }
    }

    /**
     * Sign [base64Challenge] (a SHA-256-sized hash, base64 encoded) with the card's C.AUT
     * ECC private key via `AuthSignatureService.ExternalAuthenticate`. Returns the raw
     * **DER-encoded ECDSA signature** as bytes.
     *
     * Caller is expected to convert DER → JOSE format if a JWT signature is needed
     * (the Zeta SDK's `BaseSmcbTokenProvider` handles that conversion).
     *
     * Card must be PIN-verified at the card terminal before this call; the Connector
     * does not drive PIN entry from this operation.
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun externalAuthenticate(
        cardHandle: String,
        hash: ByteArray,
    ): ByteArray {
        val base64Hash = Base64.Mime.encode(hash)
        log.debug { "ExternalAuthenticate cardHandle=$cardHandle" }
        val proxy = latestServiceProxy(ServiceNames.AuthSignatureService)
        val envelope =
            ExternalAuthenticateEnvelope(
                body =
                    ExternalAuthenticateEnvelope.Body(
                        externalAuthenticate =
                            ExternalAuthenticate(
                                cardHandle = cardHandle,
                                context = context.toApiContext(),
                                optionalInputs = ExternalAuthenticateOptionalInputs(signatureType = ECDSA_SIGNATURE_TYPE),
                                binaryString =
                                    BinaryString(
                                        base64Data = Base64Data(charData = base64Hash, mimeType = "application/octet-stream"),
                                    ),
                            ),
                    ),
            )
        val resp: ExternalAuthenticateResponseEnvelope =
            proxy.call(AuthSignatureOperations.ExternalAuthenticate, envelope)
        resp.requireSuccess("ExternalAuthenticate(cardHandle=$cardHandle)")
        val sig =
            resp.body.externalAuthenticateResponse
                ?.signatureObject
                ?.base64Signature
                ?.charData
                ?: error("ExternalAuthenticate: no Base64Signature in response")
        return Base64.Mime
            .decode(sig)
            .also { log.debug { "ExternalAuthenticate cardHandle=$cardHandle signed (${it.size}-byte DER)" } }
    }

    /**
     * Bind [cardHandle] to a Connector card session and return the session id (UUID). For
     * popp's Connector-scenario flow this UUID is sent as `clientSessionId` in the popp
     * `StartMessage`; the Connector uses it implicitly when [secureSendApdu] is called.
     * Always paired with [stopCardSession] in a `try/finally` to free the session.
     */
    suspend fun startCardSession(cardHandle: String): String {
        log.debug { "StartCardSession cardHandle=$cardHandle" }
        val proxy = serviceProxy(ServiceNames.CardService, minVersion = CARD_SERVICE_V821)
        val envelope =
            StartCardSessionEnvelope(
                body = StartCardSessionEnvelope.Body(
                    startCardSession = StartCardSession(context = context.toApiContext(), cardHandle = cardHandle),
                ),
            )
        val resp: StartCardSessionResponseEnvelope = proxy.call(CardServiceOperations.StartCardSession, envelope)
        resp.requireSuccess("StartCardSession(cardHandle=$cardHandle)")
        return resp.body.startCardSessionResponse?.sessionId
            ?.also { log.debug { "StartCardSession cardHandle=$cardHandle -> sessionId=$it" } }
            ?: error("StartCardSession: no SessionId in response")
    }

    /**
     * Release a session previously created by [startCardSession]. Idempotent: a Connector
     * "Unbekannte Session ID" fault on a session that's already gone bubbles up — callers
     * who want best-effort cleanup should wrap in `runCatching`.
     */
    suspend fun stopCardSession(sessionId: String) {
        log.debug { "StopCardSession sessionId=$sessionId" }
        val proxy = serviceProxy(ServiceNames.CardService, minVersion = CARD_SERVICE_V821)
        val envelope =
            StopCardSessionEnvelope(
                body = StopCardSessionEnvelope.Body(
                    stopCardSession = StopCardSession(sessionId = sessionId),
                ),
            )
        val resp: StopCardSessionResponseEnvelope = proxy.call(CardServiceOperations.StopCardSession, envelope)
        resp.requireSuccess("StopCardSession(sessionId=$sessionId)")
    }

    /**
     * Forward a signed scenario JWT to the Connector's `CardService.SecureSendAPDU`. The
     * Connector decodes the JWT, executes the contained APDUs against the eGK bound by
     * the active session (created via [startCardSession]), and returns the list of
     * response APDU hex strings — exactly the shape popp's `ScenarioResponseMessage.steps`
     * expects.
     */
    suspend fun secureSendApdu(signedScenario: String): List<String> {
        log.debug { "SecureSendAPDU (${signedScenario.length}-char SignedScenario JWT)" }
        val proxy = serviceProxy(ServiceNames.CardService, minVersion = CARD_SERVICE_V821)
        val envelope =
            SecureSendAPDUEnvelope(
                body = SecureSendAPDUEnvelope.Body(
                    secureSendAPDU = SecureSendAPDU(signedScenario = signedScenario),
                ),
            )
        val resp: SecureSendAPDUResponseEnvelope = proxy.call(CardServiceOperations.SecureSendAPDU, envelope)
        resp.requireSuccess("SecureSendAPDU")
        val apdus = resp.body.secureSendAPDUResponse
            ?.signedScenarioResponse
            ?.responseApduList
            ?.responseApdu
            ?: error("SecureSendAPDU: no SignedScenarioResponse in reply")
        log.debug { "SecureSendAPDU returned ${apdus.size} response APDU(s)" }
        return apdus
    }

    /**
     * Snapshot of a single SMC-B card with its [telematikId] (extracted from the C.AUT
     * cert's admission extension; `null` if extraction fails) and the parsed [certificate]
     * itself when readable.
     *
     * Use [listSmcbCards] to enumerate; [findCardHandleByTelematikId] for direct lookup.
     */
    data class SmcbCard(
        val cardHandle: String,
        val iccsn: String?,
        val cardHolderName: String?,
        val ctId: String,
        val slotId: Int,
        val telematikId: String?,
        val certificate: X509Certificate?,
    )

    /**
     * Enumerate every SMC-B currently available on the Connector, reading each card's
     * C.AUT certificate to extract its Telematik-ID. One Connector round-trip per card
     * (`ReadCardCertificate`) plus one for the card list.
     *
     * Cards whose certificate read fails are still returned with `telematikId = null`
     * and `certificate = null` so the caller can present a complete listing for the user
     * to choose from. Failures are logged at WARN.
     */
    suspend fun listSmcbCards(): List<SmcbCard> {
        val cards = getCardsByType(listOf(CardType.SmcB))
        return cards
            .map { card ->
                val cert =
                    runCatching {
                        readCardAutCertificate(card.cardHandle).parseX509()
                    }.onFailure { e ->
                        log.warn { "Could not read C.AUT for card ${card.cardHandle}: ${e.message}" }
                    }.getOrNull()
                SmcbCard(
                    cardHandle = card.cardHandle,
                    iccsn = card.iccsn,
                    cardHolderName = card.cardHolderName,
                    ctId = card.ctId,
                    slotId = card.slotId,
                    telematikId = cert?.telematikId(),
                    certificate = cert,
                )
            }.also { result ->
                log.debug {
                    "listSmcbCards: ${result.size} card(s), ${result.count { it.telematikId != null }} with Telematik-ID"
                }
            }
    }

    /**
     * Resolve a card's [iccsn] (the stable physical-card serial printed on the card body
     * and stored in `<Iccsn>`) to its (session-scoped) card handle. Faster than
     * [findCardHandleByTelematikId] — no certificate reads. Bound to the physical card,
     * so replacing the card changes the ICCSN; use Telematik-ID for legal-entity stability.
     *
     * Throws [CardNotFoundException] if no SMC-B carries this ICCSN. Multiple matches are
     * an upstream anomaly (ICCSN is unique per physical card); the first match is taken
     * with a WARN log so we don't block the operator.
     */
    suspend fun findCardHandleByIccsn(iccsn: String): String {
        log.debug { "findCardHandleByIccsn: looking up '$iccsn'" }
        val cards = getCardsByType(listOf(CardType.SmcB))
        val matches = cards.filter { it.iccsn == iccsn }
        if (matches.isEmpty()) {
            throw CardNotFoundException(
                "no SMC-B with ICCSN '$iccsn' (saw ${cards.size} card(s))",
            )
        }
        if (matches.size > 1) {
            log.warn {
                "Multiple SMC-Bs with ICCSN '$iccsn' (${matches.size}); picked ${matches.first().cardHandle}"
            }
        }
        return matches
            .first()
            .cardHandle
            .also { log.debug { "findCardHandleByIccsn '$iccsn' -> $it" } }
    }

    /**
     * Resolve a stable Telematik-ID to the (Connector-session-scoped) card handle.
     *
     * If multiple SMC-Bs carry the same Telematik-ID — typical during a card-renewal
     * window where the old card has not yet been pulled — the candidate with the **most
     * recent C.AUT cert `notBefore`** wins. Cards whose cert could not be read fall to
     * the bottom of the ordering. The decision is logged at INFO so the operator can
     * see which card was chosen.
     *
     * Throws [CardNotFoundException] when no SMC-B's C.AUT cert carries this Telematik-ID.
     */
    suspend fun findCardHandleByTelematikId(telematikId: String): String {
        log.debug { "findCardHandleByTelematikId: looking up '$telematikId'" }
        val cards = listSmcbCards()
        val matches = cards.filter { it.telematikId == telematikId }
        if (matches.isEmpty()) {
            throw CardNotFoundException(
                "no SMC-B with Telematik-ID '$telematikId' (saw ${cards.size} card(s))",
            )
        }
        // Pick the newest readable cert. `maxByOrNull` returns null for an all-null list,
        // but matches.isEmpty() is already handled above and at least the matched cards
        // *had* a telematikId, which means the cert was read — so .certificate is non-null
        // for every entry here. Belt-and-braces: filter then orderBy notBefore.
        val newest =
            matches
                .filter { it.certificate != null }
                .maxByOrNull { it.certificate!!.notBefore }
                ?: matches.first() // unreachable in practice; fall back to first match
        if (matches.size > 1) {
            log.info {
                "Multiple SMC-Bs with Telematik-ID '$telematikId' (${matches.size}); " +
                    "picked ${newest.cardHandle} (cert notBefore=${newest.certificate?.notBefore})"
            }
        }
        log.debug { "findCardHandleByTelematikId '$telematikId' -> ${newest.cardHandle}" }
        return newest.cardHandle
    }

    suspend fun getResourceInformation(): GetResourceInformationResponse {
        log.debug { "GetResourceInformation" }
        val proxy = latestServiceProxy(ServiceNames.EventService)
        val envelope =
            GetResourceInformationEnvelope(
                body =
                    GetResourceInformationEnvelope.Body(
                        getResourceInformation = GetResourceInformation(context = context.toApiContext()),
                    ),
            )
        val resp: GetResourceInformationResponseEnvelope =
            proxy.call(Operations.GetResourceInformation, envelope)
        resp.requireSuccess("GetResourceInformation")
        return resp.body.getResourceInformationResponse
            ?: error("GetResourceInformation: empty response body")
    }

    companion object {
        /**
         * Load the SDS via [httpClient] and build a client targeting [dotkon.url]. Honours
         * `rewriteServiceEndpoints`. The [httpClient] must already be configured for the
         * Connector's TLS / auth — typically via
         * [de.gematik.connector.engine.okhttp.dotkonOkHttpClient].
         */
        suspend fun connect(
            httpClient: HttpClient,
            dotkon: Dotkon,
        ): ConnectorClient {
            log.debug { "Connecting to Connector at ${dotkon.url} (rewriteServiceEndpoints=${dotkon.rewriteServiceEndpoints})" }
            val (raw, loadTime) = measureTimedValue { loadConnectorServices(httpClient, dotkon.url) }
            log.debug { "loadConnectorServices took $loadTime" }
            val (services, rewriteTime) = measureTimedValue {
                if (dotkon.rewriteServiceEndpoints) raw.withRewrittenEndpoints(dotkon.url) else raw
            }
            if (dotkon.rewriteServiceEndpoints) {
                log.debug { "Endpoint rewrite took $rewriteTime" }
            }
            log.debug {
                "Connector connected: ${services.serviceInformation.service.size} service(s) " +
                    "advertised (${services.serviceInformation.service.joinToString { it.name }})"
            }
            return ConnectorClient(
                httpClient = httpClient,
                context = dotkon.toConnectorContext(),
                services = services,
            )
        }
    }
}

/** Lookup helper used by [ConnectorClient]; exposed for callers who hold a bare [ConnectorServices]. */
fun ConnectorServices.findLatest(serviceName: String): Pair<Service, ServiceVersion>? {
    val svc = serviceInformation.service.firstOrNull { it.name == serviceName } ?: return null
    val ver = svc.versions.version.maxByOrNull { semverAsNumber(it.version) } ?: return null
    return svc to ver
}

class ServiceNotFoundException(
    message: String,
) : ConnectorException(message)

class CardNotFoundException(
    message: String,
) : ConnectorException(message)
