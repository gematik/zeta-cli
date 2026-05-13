package de.gematik.zeta.cli.lifecycle

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import de.gematik.zeta.cli.client.ZetaSessionCommand
import de.gematik.zeta.cli.client.originOf
import de.gematik.zeta.cli.output.OutputFormat
import de.gematik.zeta.cli.output.renderJson
import de.gematik.zeta.cli.output.renderSections
import de.gematik.zeta.cli.state.renderEntryJson
import de.gematik.zeta.cli.state.renderEntryText
import de.gematik.zeta.sdk.SdkStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private val log = KotlinLogging.logger {}

/**
 * `zeta login URL` — idempotent shortcut for "get me a usable access token for this
 * resource, doing whichever steps aren't already cached". Equivalent to
 * `zeta register URL && zeta authenticate URL` but skips the registration call when the
 * client is already registered and skips the token exchange when the cached access token
 * is still valid.
 *
 * Prints a small trace before the resulting status so the user knows what actually ran —
 * the whole point of having `login` as a separate verb from `authenticate`.
 */
class LoginCommand : ZetaSessionCommand(name = "login") {
    private val url: String by argument(
        name = "URL",
        help = "URL of the Zeta-protected resource to log into.",
    )

    private val scopes: List<String> by option(
        "-s", "--scope",
        metavar = "NAME",
        help = "OAuth2 scope to request. Repeatable; at least one is required.",
    ).multiple(required = true)

    private val reveal: Boolean by option(
        "--reveal",
        help = "Include normally-redacted secrets in the resulting status output.",
    ).flag(default = false)

    override fun help(context: Context) =
        "Register (if needed) and authenticate against the resource. Idempotent — skips steps already cached."

    override fun runCommand() {
        val resource = originOf(url)
        log.info { "Logging in to $resource with scopes $scopes" }
        openSession(resource = resource, scopes = scopes) { sdk, _ ->
            val initial = runBlocking { sdk.status().getOrThrow() }

            val ranRegister = initial == SdkStatus.NOT_REGISTERED
            if (ranRegister) {
                log.debug { "Status was NOT_REGISTERED — running register()" }
                runBlocking { sdk.register().getOrThrow() }
            }

            val ranAuthenticate = initial != SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN
            if (ranAuthenticate) {
                log.debug { "Initial status was $initial — running authenticate()" }
                runBlocking { sdk.authenticate().getOrThrow() }
            }

            renderLogin(resource, ranRegister, ranAuthenticate)
        }
    }

    private fun renderLogin(resource: String, ranRegister: Boolean, ranAuthenticate: Boolean) {
        val entry = loadEntry(resource)
        when (cliConfig.outputFormat) {
            OutputFormat.JSON -> echo(
                renderJson(
                    buildJsonObject {
                        put(
                            "trace",
                            buildJsonObject {
                                put("register", JsonPrimitive(if (ranRegister) "ran" else "skipped"))
                                put("authenticate", JsonPrimitive(if (ranAuthenticate) "ran" else "skipped"))
                            },
                        )
                        put("result", renderEntryJson(entry, reveal))
                    },
                    colorize = colorize,
                ),
            )

            OutputFormat.TEXT, OutputFormat.RAW -> {
                echo(
                    renderSections(colorize = colorize) {
                        section("Login trace") {
                            field("register", if (ranRegister) "ran" else "skipped (already registered)")
                            field(
                                "authenticate",
                                if (ranAuthenticate) "ran" else "skipped (token still valid)",
                            )
                        }
                    },
                )
                echo("")
                echo(renderEntryText(entry, colorize, reveal))
            }
        }
    }
}
