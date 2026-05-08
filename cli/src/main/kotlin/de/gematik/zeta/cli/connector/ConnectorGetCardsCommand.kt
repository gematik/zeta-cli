package de.gematik.zeta.cli.connector

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.mordant.rendering.TextStyles
import de.gematik.connector.api.gematik.conn.cardservice81.Card
import de.gematik.connector.api.gematik.conn.cardservicecommon20.CardType
import de.gematik.zeta.cli.ZetaCliktCommand
import de.gematik.zeta.cli.output.OutputFormat
import de.gematik.zeta.cli.output.renderJson
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

private val log = KotlinLogging.logger {}

/**
 * `zeta connector get cards` — list every card the Connector sees in its terminals. Useful
 * for picking the right `--connector-card-handle` / `--connector-telematik-id` for the SMC-B
 * authentication flow, or the eGK card-handle argument for `zeta popp connector`.
 */
class ConnectorGetCardsCommand : ZetaCliktCommand(name = "cards") {
    override fun help(context: Context) =
        "List cards visible to the Connector (SMC-B, eGK, HBA, …)."

    override fun runCommand() {
        val session = openConnectorSession(
            connectorConfigName = cliConfig.connectorConfig,
            connectTimeout = cliConfig.connectTimeout,
            requestTimeout = cliConfig.requestTimeout,
            proxy = cliConfig.proxy,
        )
        try {
            val cards = runBlocking { session.connector().getAllCards() }
            log.debug { "Connector reported ${cards.size} card(s)" }
            when (cliConfig.outputFormat) {
                OutputFormat.JSON -> echo(renderJson(jsonReport(cards), colorize = colorize))
                OutputFormat.TEXT, OutputFormat.RAW -> echo(textReport(cards))
            }
        } finally {
            session.close()
        }
    }
}

/** Map our enum constant to the gematik wire string (EGK, SMC-B, HBA, …). */
private fun CardType.wireName(): String = when (this) {
    CardType.Egk -> "EGK"
    CardType.HBAQSig -> "HBA-qSig"
    CardType.Hba -> "HBA"
    CardType.SmcB -> "SMC-B"
    CardType.HsmB -> "HSM-B"
    CardType.SmcKt -> "SMC-KT"
    CardType.Kvk -> "KVK"
    CardType.Zod20 -> "ZOD_2.0"
    CardType.Unknown -> "UNKNOWN"
    CardType.HBAx -> "HBAx"
    CardType.SmB -> "SM-B"
}

private fun jsonReport(cards: List<Card>): JsonArray = buildJsonArray {
    cards.forEach { c ->
        addJsonObject {
            put("cardHandle", c.cardHandle)
            put("type", c.cardType.wireName())
            put("ctId", c.ctId)
            put("slot", c.slotId)
            put("iccsn", c.iccsn?.let(::JsonPrimitive) ?: JsonNull)
            put("kvnr", c.kvnr?.let(::JsonPrimitive) ?: JsonNull)
            put("cardHolder", c.cardHolderName?.let(::JsonPrimitive) ?: JsonNull)
            put("insertedAt", c.insertTime)
        }
    }
}

private fun textReport(cards: List<Card>): String {
    if (cards.isEmpty()) return "No cards visible to the Connector."

    // Column layout: TYPE / SLOT / HANDLE / ICCSN / HOLDER. KVNR + insertion time are
    // useful but rarely fit on a terminal line; surface them in `-o json` only.
    val typeW = maxOf("TYPE".length, cards.maxOf { it.cardType.wireName().length })
    val slotW = maxOf("SLOT".length, cards.maxOf { "${it.ctId}/${it.slotId}".length })
    val handleW = maxOf("HANDLE".length, cards.maxOf { it.cardHandle.length })
    val iccsnW = maxOf("ICCSN".length, cards.maxOf { (it.iccsn ?: "-").length })
    val header = TextStyles.bold(
        "${"TYPE".padEnd(typeW)}  " +
            "${"SLOT".padEnd(slotW)}  " +
            "${"HANDLE".padEnd(handleW)}  " +
            "${"ICCSN".padEnd(iccsnW)}  " +
            "HOLDER",
    )
    return buildString {
        appendLine(header)
        cards.forEach { c ->
            val slot = "${c.ctId}/${c.slotId}"
            val holder = c.cardHolderName ?: c.kvnr ?: "-"
            appendLine(
                "${c.cardType.wireName().padEnd(typeW)}  " +
                    "${slot.padEnd(slotW)}  " +
                    "${c.cardHandle.padEnd(handleW)}  " +
                    "${(c.iccsn ?: "-").padEnd(iccsnW)}  " +
                    holder,
            )
        }
    }.trimEnd()
}
