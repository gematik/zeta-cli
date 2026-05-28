package de.gematik.zeta.cli.state

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.theme
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import de.gematik.zeta.cli.ZetaProfileCommand
import de.gematik.zeta.cli.client.originOf
import de.gematik.zeta.cli.output.OutputFormat
import de.gematik.zeta.cli.output.renderJson
import de.gematik.zeta.cli.output.renderSections
import de.gematik.zeta.cli.storage.zetaProfilePath
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.Url
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

private val log = KotlinLogging.logger {}

/**
 * `zeta status [URL]` — full local view of the SDK's auth state for the active profile.
 *
 * Reads exclusively from the profile's storage file; never builds the SDK, never hits the
 * network. Auth options are not required at any depth — the data shown is already cached
 * on disk. To populate the cache, run `zeta inspect`, `zeta register`, or `zeta login`
 * first.
 *
 * Output always includes (when present): the resource, the linked authorization server,
 * the [de.gematik.zeta.sdk.SdkStatus] enum, the decoded access-token header + claims, and
 * a registration summary. `--reveal` adds the raw access-token JWS, the dynamic-client
 * `client_secret`, and the `registration_access_token`. Refresh tokens are never emitted
 * (only their presence informs the status enum).
 *
 * The status enum mirrors `ZetaSdkClient.status()` from SDK 1.0.1+: tokens are looked up
 * by the build-time resource URL (the same string `AccessTokenProviderImpl` saves under).
 * See [entryFor].
 */
class StatusCommand : ZetaProfileCommand(name = "status") {
    private val url: String? by argument(
        name = "URL",
        help = "URL of a Zeta-protected resource. Without it, lists status for every " +
            "cached resource in the active profile.",
    ).optional()

    private val reveal: Boolean by option(
        "--reveal",
        envvar = "ZETA_REVEAL",
        help = "Include normally-redacted secrets in the output: the raw access-token JWS, " +
            "the dynamic-client `client_secret`, and the `registration_access_token`. " +
            "Refresh tokens are never emitted regardless. (env: ZETA_REVEAL)",
    ).flag(default = false)

    override fun help(context: Context) =
        "Show local SDK state (registration + token validity) for one resource or every cached resource."

    override fun runCommand() {
        val target = url
        if (target != null) {
            val resource = originOf(target)
            log.debug { "Looking up status for resource $resource" }
            renderEntry(loadEntry(resource), reveal)
        } else {
            val path = zetaProfilePath(profile)
            log.debug { "Enumerating resources from $path" }
            val entries = runBlocking { enumerateEntries(ProfileStores(path)) }
            renderProfile(entries, path)
        }
    }

    private fun renderProfile(entries: List<Entry>, path: Path) {
        when (cliConfig.outputFormat) {
            OutputFormat.JSON -> echo(
                renderJson(
                    buildJsonObject {
                        put("profile", JsonPrimitive(profile))
                        put("path", JsonPrimitive(path.toString()))
                        put("resources", buildJsonArray { entries.forEach { add(renderEntryJson(it, reveal)) } })
                    },
                    colorize = colorize,
                ),
            )

            OutputFormat.TEXT, OutputFormat.RAW -> echo(profileText(entries, path))
        }
    }

    private fun profileText(entries: List<Entry>, path: Path): String = buildString {
        appendLine(
            renderSections(colorize = colorize) {
                section("Profile") {
                    field("Profile", profile)
                    field("Path", path.toString())
                }
            },
        )
        if (entries.isEmpty()) {
            val msg = "No cached resources for this profile."
            appendLine(if (colorize) currentContext.theme.muted(msg) else msg)
            return@buildString
        }
        entries.forEach { entry ->
            appendLine()
            append(renderEntryText(entry, colorize, reveal))
            appendLine()
        }
    }.trimEnd()
}
