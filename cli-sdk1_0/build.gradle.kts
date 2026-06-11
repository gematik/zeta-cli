plugins {
    id("buildlogic.kotlin-application-conventions")
    kotlin("plugin.serialization")
}

// Parallel cli build pinned to the legacy SDK release (1.0.x). Same Kotlin source as :cli;
// only the SDK dependency differs, which is enough for the Kotlin compiler to emit bytecode
// that matches the 1.0.x synthetic-constructor / method shapes (where it differs from the
// catalog default — see ZetaHttpClientBuilder primary constructor change).
val zetaSdkVersion: String =
    providers.gradleProperty("zetaSdkLegacyVersion").orElse(libs.versions.zeta.sdk.legacy).get()

dependencies {
    implementation(project(":connector"))
    implementation("de.gematik.zeta:zeta-sdk-jvm:$zetaSdkVersion") {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    implementation(libs.clikt)
    implementation(libs.kotlin.logging)
    implementation(libs.logback.classic)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jna)
    implementation(libs.snakeyaml)
}

application {
    mainClass = "de.gematik.zeta.cli.MainKt"
    applicationName = "zeta-sdk1_0"
}

// Share the cli's source tree — same Kotlin files, just compiled against a different SDK.
sourceSets["main"].kotlin.srcDir(project(":cli").file("src/main/kotlin"))
sourceSets["main"].resources.srcDir(project(":cli").file("src/main/resources"))
sourceSets["test"].kotlin.srcDir(project(":cli").file("src/test/kotlin"))
sourceSets["test"].resources.srcDir(project(":cli").file("src/test/resources"))

val generateBuildConfig by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/sources/buildconfig/kotlin/main")
    val versionStr = providers.provider { project.version.toString() }
    val resolvedSdkVersion = zetaSdkVersion
    inputs.property("version", versionStr)
    inputs.property("zetaSdkVersion", resolvedSdkVersion)
    outputs.dir(outputDir)
    doLast {
        val pkgDir = outputDir.get().asFile.resolve("de/gematik/zeta/cli")
        pkgDir.mkdirs()
        pkgDir.resolve("BuildConfig.kt").writeText(
            """
            package de.gematik.zeta.cli

            internal object BuildConfig {
                const val VERSION: String = "${versionStr.get()}"
                const val ZETA_SDK_VERSION: String = "$resolvedSdkVersion"
            }

            """.trimIndent()
        )
    }
}

kotlin {
    sourceSets["main"].kotlin.srcDir(generateBuildConfig)
}
