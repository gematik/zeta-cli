package de.gematik.zeta.cli.sdk

import com.github.ajalt.clikt.core.Context
import de.gematik.zeta.cli.ZetaCliktCommand

/**
 * Parent for `zeta sdk …` subcommands. The launcher resolves which `zeta-sdk` version is
 * loaded (see `de.gematik.zeta.launcher.LauncherKt`); these subcommands inspect and
 * mutate the sticky pointer that drives the launcher when no flag/env is set.
 */
class SdkCommand : ZetaCliktCommand(name = "sdk") {
    override fun help(context: Context) =
        "Inspect or change the active zeta-sdk version. The launcher resolves --sdk → " +
            "ZETA_SDK → \$XDG_CONFIG_HOME/telematik/zeta/sdk (written by `zeta sdk use`) " +
            "→ newest bundled version."
}
