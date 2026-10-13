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
package de.gematik.zeta.cli.serve

import de.gematik.zeta.cli.vsdm.MIDDLEWARE_INSURANT_ID_HEADER
import de.gematik.zeta.cli.vsdm.MIDDLEWARE_INSURER_ID_HEADER
import de.gematik.zeta.stress.identity.PoppJwt
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** A minted PoPP token and the claims a caller would otherwise have to decode it for. */
@Serializable
internal data class PoppTokenDto(
    val popp: String,
    val actorId: String,
    val insurerId: String,
    val insurantId: String,
    val patientProofTime: Long?,
    val iat: Long?,
)

/**
 * `GET /api/popp/token` — mint a PoPP token and hand it back, without reading anything.
 *
 * The counterpart to `/api/vsdm/popp-then-read` for callers that want one proof of presence and then
 * several reads: minting touches the card and dominates the cost of a read, so tying the two together
 * hides what the bundle cache saves. `GET` for consistency with `popp-then-read`, which opens a card
 * session just the same.
 */
internal suspend fun handlePoppToken(call: ApplicationCall, ctx: DaemonContext) {
    if (ctx.poppMint == null) {
        return respondError(
            call,
            HttpStatusCode.NotImplemented,
            "PoPP not available; start zeta serve with --popp-card connector|standard",
        )
    }
    val egkHandle = call.request.headers[MIDDLEWARE_EGK_HEADER]

    val token = ctx.requestMutex.withLock {
        when (val minted = mintPoppToken(ctx, egkHandle)) {
            is MintResult.Ok -> minted.token
            is MintResult.Failed -> return respondError(call, minted.status, minted.message)
        }
    }

    val claims = PoppJwt.parse(token)
        ?: return respondError(call, HttpStatusCode.BadGateway, "PoPP token could not be parsed")

    call.response.headers.append(MIDDLEWARE_POPP_HEADER, token)
    call.response.headers.append(MIDDLEWARE_INSURER_ID_HEADER, claims.insurerId)
    call.response.headers.append(MIDDLEWARE_INSURANT_ID_HEADER, claims.patientId)
    call.respond(
        PoppTokenDto(
            popp = token,
            actorId = claims.actorId,
            insurerId = claims.insurerId,
            insurantId = claims.patientId,
            patientProofTime = claims.proofTime,
            iat = claims.iat,
        ),
    )
}
