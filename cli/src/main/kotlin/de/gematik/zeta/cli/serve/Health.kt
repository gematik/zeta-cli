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

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Simplified live-runtime view for `GET /api/health` — the open warm sessions + connector state. */
@Serializable
internal data class HealthDto(
    val status: String,
    val env: String,
    val poppCard: String?,
    val warmup: WarmupDto,
    val sessions: List<SessionDto>,
    val connector: ConnectorDto,
    val cache: CacheDto? = null,
)

/** Bundle-cache counters; absent when `--cache-db` was not given. */
@Serializable
internal data class CacheDto(val entries: Long, val oldestRevalidation: Long?, val fileBytes: Long)

@Serializable
internal data class SessionDto(val resource: String, val scopes: List<String>)

/** Startup warm-sweep progress. [total] is null until the catalog resolves; [warmed] is the open-session count. */
@Serializable
internal data class WarmupDto(val complete: Boolean, val warmed: Int, val total: Int?)

/** Connector-session state. Non-secret `.kon` identity only — never credentials. */
@Serializable
internal data class ConnectorDto(
    val configured: Boolean,
    val connected: Boolean? = null,
    val url: String? = null,
    val mandantId: String? = null,
    val workplaceId: String? = null,
    val clientSystemId: String? = null,
)

/**
 * `GET /api/health` — a lightweight liveness view of the running daemon: always `200`, with the open
 * warm sessions and the connector-session state. Cheap: it reads in-memory daemon state only (no profile
 * DB enumeration, no session re-probe).
 */
internal suspend fun handleHealth(call: ApplicationCall, ctx: DaemonContext) {
    val connector = ctx.connectorSession?.let {
        ConnectorDto(
            configured = true,
            connected = it.isConnected(),
            url = it.dotkon.url,
            mandantId = it.dotkon.mandantId,
            workplaceId = it.dotkon.workplaceId,
            clientSystemId = it.dotkon.clientSystemId,
        )
    } ?: ConnectorDto(configured = false)

    val sessions = ctx.openSessions()
    call.respond(
        HealthDto(
            status = "ok",
            env = ctx.env.name.lowercase(),
            poppCard = ctx.poppMint?.transport?.name?.lowercase(),
            warmup = WarmupDto(complete = ctx.warmupComplete, warmed = sessions.size, total = ctx.warmupTotal),
            sessions = sessions.map { SessionDto(it.resource, it.scopes) },
            connector = connector,
            cache = ctx.vsdmCache?.let {
                withContext(Dispatchers.IO) {
                    CacheDto(it.entryCount(), it.oldestRevalidationEpochSec(), ctx.cacheFileBytes() ?: 0)
                }
            },
        ),
    )
}
