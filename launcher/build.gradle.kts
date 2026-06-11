import org.gradle.api.tasks.application.CreateStartScripts

plugins {
    id("buildlogic.kotlin-application-conventions")
}

application {
    mainClass = "de.gematik.zeta.launcher.LauncherKt"
    applicationName = "zeta"
}

// Prepend `chcp 65001` to the Windows launcher so the console renders UTF-8 correctly,
// matching the cli module's start-script handling.
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        val bat = windowsScript
        bat.writeText("@chcp 65001 >NUL\r\n" + bat.readText())
    }
}
