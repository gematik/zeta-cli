package de.gematik.zeta.cli.vsdm

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import de.gematik.zeta.cli.ZetaCliktCommand
import de.gematik.zeta.cli.output.OutputFormat
import de.gematik.zeta.cli.output.renderJson
import de.gematik.zeta.cli.output.renderSections
import de.gematik.zeta.cli.cache.CacheDb
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `zeta vsdm cache` — inspect and clear the bundle cache that `--cache-db` writes. */
internal class VsdmCacheCommand : ZetaCliktCommand(name = "cache") {
    override fun help(context: Context) = "Inspect or clear the VSDM bundle cache."
}

/** Shared by the verbs below: they all act on one named cache file, so the option is required here. */
internal abstract class CacheFileCommand(name: String) : ZetaCliktCommand(name = name) {
    protected val dbPath: Path by option(
        "--cache-db",
        metavar = "FILE",
        envvar = "ZETA_CACHE_DB",
        help = "The cache file to act on. (env: ZETA_CACHE_DB)",
    ).path(canBeFile = true, canBeDir = false).required()

    /**
     * Opening a [CacheDb] creates the file, which is right for a read but wrong for these verbs:
     * `stats` on a mistyped path should say so, not leave an empty cache behind.
     */
    protected fun <T> withCache(block: (CacheDb, VsdmBundleCache) -> T): T {
        if (!dbPath.exists()) throw CliktError("no cache at $dbPath")
        return CacheDb(dbPath).use { db -> block(db, VsdmBundleCache(db)) }
    }
}

@Serializable
private data class CacheStatsOutput(
    val file: String,
    val entries: Long,
    val actors: Long,
    val oldestRevalidation: String?,
    val bytes: Long,
)

internal class VsdmCacheStatsCommand : CacheFileCommand(name = "stats") {
    override fun help(context: Context) = "Print how much the cache holds."

    override fun runCommand() {
        val out = withCache { db, bundles ->
            CacheStatsOutput(
                file = dbPath.toString(),
                entries = bundles.entryCount(),
                actors = bundles.actorCount(),
                oldestRevalidation = bundles.oldestRevalidationEpochSec()?.let { Instant.ofEpochSecond(it).toString() },
                bytes = db.fileBytes(),
            )
        }
        if (cliConfig.outputFormat == OutputFormat.JSON) {
            echo(renderJson(Json.encodeToJsonElement(CacheStatsOutput.serializer(), out), colorize = colorize))
        } else {
            echo(
                renderSections(colorize = colorize) {
                    section("VSDM bundles") {
                        field("File", out.file)
                        field("Entries", out.entries.toString())
                        field("Identities", out.actors.toString())
                        field("Oldest revalidation", out.oldestRevalidation ?: "—")
                        field("Size", "${out.bytes} bytes")
                    }
                },
            )
        }
    }
}

internal class VsdmCachePurgeCommand : CacheFileCommand(name = "purge") {
    private val actor: String? by option(
        "--actor",
        metavar = "TID",
        help = "Only the entries read by this SMC-B Telematik-ID.",
    )
    private val insurer: String? by option("--insurer", metavar = "IKNR", help = "Only this insurer's entries.")

    private val insurant: String? by option(
        "--insurant",
        metavar = "KVNR",
        help = "Only this insurant's entries.",
    )
    private val all: Boolean by option(
        "--all",
        help = "Every entry. Required when no other filter narrows the purge.",
    ).flag(default = false)
    private val tokensOnly: Boolean by option(
        "--tokens",
        help = "Clear only the stored PoPP tokens, keeping the bundles. Honours the filters above.",
    ).flag(default = false)
    private val force: Boolean by option(
        "--force",
        envvar = "ZETA_CACHE_PURGE_FORCE",
        help = "Skip the interactive confirmation for --all. Required in non-interactive (scripted) " +
            "mode. (env: ZETA_CACHE_PURGE_FORCE)",
    ).flag(default = false)

    override fun help(context: Context) = "Delete cached bundles, or just the stored PoPP tokens."

    override fun runCommand() {
        val narrowed = actor != null || insurer != null || insurant != null
        if (!narrowed && !all) {
            throw UsageError("narrow the purge with --actor / --insurer / --insurant, or pass --all to delete everything")
        }
        // Only an unnarrowed purge is the destructive one; a filtered delete names what it removes.
        if (!narrowed && !confirmDestructive(purgeQuestion(), force)) return

        if (tokensOnly) {
            val cleared = withCache { _, bundles -> bundles.clearPoppTokens(actor, insurer, insurant) }
            echo("cleared the PoPP token on $cleared entries")
            return
        }
        echo("deleted ${withCache { _, bundles -> bundles.purge(actor, insurer, insurant) }} entries")
    }

    private fun purgeQuestion(): String =
        if (tokensOnly) "Clear every stored PoPP token in $dbPath?" else "Delete every cached bundle in $dbPath?"
}
