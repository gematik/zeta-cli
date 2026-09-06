package de.gematik.zeta.cli.vsdm

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

    protected fun <T> withCache(block: (CacheDb, VsdmBundleCache) -> T): T =
        CacheDb(dbPath).use { db -> block(db, VsdmBundleCache(db)) }
}

@Serializable
private data class CacheStatsOutput(
    val file: String,
    val entries: Long,
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
                        field("Oldest revalidation", out.oldestRevalidation ?: "—")
                        field("Size", "${out.bytes} bytes")
                    }
                },
            )
        }
    }
}

internal class VsdmCachePurgeCommand : CacheFileCommand(name = "purge") {
    private val insurer: String? by option("--insurer", metavar = "IKNR", help = "Only this insurer's entries.")
    private val patient: String? by option("--patient", metavar = "KVNR", help = "Only this insurant's entries.")
    private val all: Boolean by option(
        "--all",
        help = "Every entry. Required when neither --insurer nor --patient narrows the purge.",
    ).flag(default = false)
    private val tokensOnly: Boolean by option(
        "--tokens",
        help = "Clear only the stored PoPP tokens, keeping the bundles.",
    ).flag(default = false)

    override fun help(context: Context) = "Delete cached bundles, or just the stored PoPP tokens."

    override fun runCommand() {
        if (tokensOnly) {
            echo("cleared the PoPP token on ${withCache { _, bundles -> bundles.clearPoppTokens() }} entries")
            return
        }
        if (insurer == null && patient == null && !all) {
            throw UsageError("narrow the purge with --insurer / --patient, or pass --all to delete everything")
        }
        echo("deleted ${withCache { _, bundles -> bundles.purge(insurer, patient) }} entries")
    }
}
