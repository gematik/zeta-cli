package de.gematik.zeta.cli.serve

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
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
)

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
        ),
    )
}
