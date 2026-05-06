package de.gematik.zeta.cli.get

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.theme
import de.gematik.zeta.cli.ZetaCliktCommand
import de.gematik.zeta.cli.output.OutputFormat
import de.gematik.zeta.cli.output.renderJson
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonArray

private val log = KotlinLogging.logger {}

class GetClientsCommand : ZetaCliktCommand(name = "clients") {
    override fun help(context: Context) =
        "List the registered OAuth2 clients and their information."

    override fun runCommand() {
        log.info { "Listing OAuth2 clients" }
        when (cliConfig.outputFormat) {
            OutputFormat.JSON -> echo(renderJson(JsonArray(emptyList()), colorize = colorize))
            OutputFormat.TEXT, OutputFormat.RAW -> echo(currentContext.theme.muted("OAuth2 client listing is not yet implemented."))
        }
    }
}
