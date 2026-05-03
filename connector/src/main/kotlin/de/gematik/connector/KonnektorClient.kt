package de.gematik.connector

import de.gematik.connector.api.gematik.conn.authsignatureservice74.ExternalAuthenticateEnvelope
import de.gematik.connector.api.gematik.conn.authsignatureservice74.ExternalAuthenticateResponseEnvelope
import de.gematik.connector.api.gematik.conn.cardservice81.Card
import de.gematik.connector.api.gematik.conn.cardservicecommon20.CardType
import de.gematik.connector.api.gematik.conn.certificateservice601.CryptType
import de.gematik.connector.api.gematik.conn.certificateservice601.ReadCardCertificate
import de.gematik.connector.api.gematik.conn.certificateservice601.ReadCardCertificateCertRefList
import de.gematik.connector.api.gematik.conn.certificateservice601.ReadCardCertificateEnvelope
import de.gematik.connector.api.gematik.conn.certificateservice601.ReadCardCertificateResponseEnvelope
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
import de.gematik.connector.api.gematik.conn.authsignatureservice741.Operations as AuthSignatureOperations
import de.gematik.connector.api.gematik.conn.certificateservice601.Operations as CertificateServiceOperations

private val log = KotlinLogging.logger {}

/**
 * Standard signature-type URI for ECDSA on SMC-B / HBA cards (BSI TR-03111).
 * Required by the Konnektor's `ExternalAuthenticate` to pick the ECC card key.
 */
private const val ECDSA_SIGNATURE_TYPE = "urn:bsi:tr:03111:ecdsa"

/**
 * Client-side context (`<ContextType>` in WSDL parlance) — identifies the calling
 * mandant, workplace, client system and (optionally) user against the Konnektor.
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
 * High-level Konnektor client.
 *
 * The HTTP client is **provided by the caller** — the lib does not own its lifecycle,
 * does not know which engine it uses, and never closes it. Use
 * [de.gematik.connector.engine.okhttp.dotkonOkHttpClient] for the common .kon-driven
 * case, or build your own. Callers must close the [HttpClient] when they're done.
 *
 * Construct via [connect] (which loads the SDS) or directly when SDS contents are
 * already in hand (e.g. cached or test-injected).
 */
class KonnektorClient(
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

    // ----- convenience methods (mirror koap-go's Client) ------------------------------

    /** `EventService.GetCards` with no filter — returns every card the Konnektor sees. */
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
     * Throws [SoapFaultException] if the Konnektor reports a fault (card missing, PIN
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
     * Card must be PIN-verified at the card terminal before this call; the Konnektor
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
     * Enumerate every SMC-B currently available on the Konnektor, reading each card's
     * C.AUT certificate to extract its Telematik-ID. One Konnektor round-trip per card
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
     * Resolve a stable Telematik-ID to the (Konnektor-session-scoped) card handle.
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
         * Konnektor's TLS / auth — typically via
         * [de.gematik.connector.engine.okhttp.dotkonOkHttpClient].
         */
        suspend fun connect(
            httpClient: HttpClient,
            dotkon: Dotkon,
        ): KonnektorClient {
            log.debug { "Connecting to Konnektor at ${dotkon.url} (rewriteServiceEndpoints=${dotkon.rewriteServiceEndpoints})" }
            val raw = loadConnectorServices(httpClient, dotkon.url)
            val services =
                if (dotkon.rewriteServiceEndpoints) {
                    raw.withRewrittenEndpoints(dotkon.url)
                } else {
                    raw
                }
            log.debug {
                "Konnektor connected: ${services.serviceInformation.service.size} service(s) " +
                    "advertised (${services.serviceInformation.service.joinToString { it.name }})"
            }
            return KonnektorClient(
                httpClient = httpClient,
                context = dotkon.toConnectorContext(),
                services = services,
            )
        }
    }
}

/** Lookup helper used by [KonnektorClient]; exposed for callers who hold a bare [ConnectorServices]. */
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
