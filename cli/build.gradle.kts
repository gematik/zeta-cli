import org.gradle.api.tasks.bundling.Compression

plugins {
    id("buildlogic.kotlin-application-conventions")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":connector"))
    implementation(libs.zeta.sdk) {
        // zeta-sdk transitively pulls slf4j-simple, which clashes with our Logback binding.
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    implementation(libs.clikt)
    implementation(libs.kotlin.logging)
    implementation(libs.logback.classic)
    // OkHttp is the single engine for every CLI-owned Ktor client. Routes through JSSE
    // for mTLS (so brainpool-ECC SMC-B / HBA / KSP certs are sent — Ktor CIO's TLS stack
    // hard-codes RSA/DSS only) and exposes `proxyAuthenticator` for HTTP-CONNECT proxy
    // auth (Ktor CIO doesn't preemptively send `Proxy-Authorization`, which presents as
    // `SocketException: Connection reset` against authenticating corporate proxies).
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.logging)
    implementation(libs.kotlinx.serialization.json)
    // Already on the runtime classpath via mordant-jvm-jna; declared here so we can call
    // isatty(2) directly to detect whether stderr is a TTY (see term/StderrColors.kt).
    implementation(libs.jna)
    // YAML parser backing the config-file value source (./zeta.yaml and the XDG fallback).
    implementation(libs.snakeyaml)
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
