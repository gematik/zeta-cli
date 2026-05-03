import org.gradle.api.tasks.bundling.Compression

plugins {
    id("buildlogic.kotlin-application-conventions")
}

dependencies {
    implementation(project(":api"))
    implementation(project(":connector"))
    implementation(libs.clikt)
    implementation(libs.kotlin.logging)
    implementation(libs.logback.classic)
    implementation(libs.ktor.client.cio)
    // OkHttp is used by the kon command for mutual TLS — Ktor CIO's TLS implementation
    // doesn't reliably present client certs when the server's CertificateRequest doesn't
    // list a matching CA name, while OkHttp routes through JSSE which is more permissive.
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.logging)
    implementation(libs.kotlinx.serialization.json)
}

application {
    mainClass = "de.gematik.zeta.cli.MainKt"
    applicationName = "zeta"
}

val generateBuildConfig by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/sources/buildconfig/kotlin/main")
    val versionStr = providers.provider { project.version.toString() }
    val zetaSdkVersion = libs.versions.zeta.sdk.get()
    inputs.property("version", versionStr)
    inputs.property("zetaSdkVersion", zetaSdkVersion)
    outputs.dir(outputDir)
    doLast {
        val pkgDir = outputDir.get().asFile.resolve("de/gematik/zeta/cli")
        pkgDir.mkdirs()
        pkgDir.resolve("BuildConfig.kt").writeText(
            """
            package de.gematik.zeta.cli

            internal object BuildConfig {
                const val VERSION: String = "${versionStr.get()}"
                const val ZETA_SDK_VERSION: String = "$zetaSdkVersion"
            }

            """.trimIndent()
        )
    }
}

kotlin {
    sourceSets["main"].kotlin.srcDir(generateBuildConfig)
}

tasks.named<Tar>("distTar") {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}
