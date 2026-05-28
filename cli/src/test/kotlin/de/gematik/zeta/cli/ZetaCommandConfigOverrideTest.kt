package de.gematik.zeta.cli

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import de.gematik.zeta.cli.config.ConfigFileMissingException
import de.gematik.zeta.cli.config.resolveConfigFile
import de.gematik.zeta.cli.output.OutputFormat
import java.nio.file.Path
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ZetaCommandConfigOverrideTest {

    /** Minimal subcommand that just snapshots [CliConfig] after parent run() has populated it. */
    private class CaptureSub : ZetaCliktCommand("capture") {
        var captured: OutputFormat? = null
        override fun runCommand() {
            captured = cliConfig.outputFormat
        }
    }

    @Test
    fun `configPath supplies option values via the YAML value source`(@TempDir dir: Path) {
        val cfg = dir.resolve("zeta.yaml")
        cfg.writeText("output-format: json\n")

        val sub = CaptureSub()
        ZetaCommand(configPath = cfg).subcommands(sub).parse(listOf("capture"))

        assertEquals(OutputFormat.JSON, sub.captured)
    }

    @Test
    fun `resolveConfigFile throws when override path is missing`(@TempDir dir: Path) {
        val missing = dir.resolve("not-there.yaml")
        val ex = assertThrows(ConfigFileMissingException::class.java) {
            resolveConfigFile(missing)
        }
        assertEquals(missing, ex.path)
    }

    @Test
    fun `resolveConfigFile returns override verbatim when it exists`(@TempDir dir: Path) {
        val cfg = dir.resolve("zeta.yaml")
        cfg.writeText("# empty\n")
        assertEquals(cfg, resolveConfigFile(cfg))
    }

    @Test
    fun `null configPath disables YAML loading even when a file exists`(@TempDir dir: Path) {
        // This is the --no-config code path: Main.kt sets configPath = null, the value
        // source is never installed, and built-in defaults apply — even if a YAML on disk
        // would say otherwise.
        val cfg = dir.resolve("zeta.yaml")
        cfg.writeText("output-format: json\n")

        val sub = CaptureSub()
        ZetaCommand(configPath = null).subcommands(sub).parse(listOf("capture"))

        assertEquals(OutputFormat.TEXT, sub.captured)
    }

    @Test
    fun `CLI flag still wins over YAML value supplied via configPath`(@TempDir dir: Path) {
        // Sanity check that overlay precedence (CLI > value source) is not broken by the
        // configPath plumbing — the file says json, but --output-format text on the CLI
        // wins. Flag is passed at child depth to dodge the documented sticky-option
        // chain-merge caveat (where a sticky option set at the parent gets re-read and
        // overwritten by the child's own resolution).
        val cfg = dir.resolve("zeta.yaml")
        cfg.writeText("output-format: json\n")

        val sub = CaptureSub()
        ZetaCommand(configPath = cfg).subcommands(sub)
            .parse(listOf("capture", "-o", "text"))

        assertEquals(OutputFormat.TEXT, sub.captured)
    }
}
