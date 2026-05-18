import org.gradle.api.tasks.bundling.Compression

plugins {
    id("buildlogic.kotlin-application-conventions")
    kotlin("plugin.serialization")
}

// SDK version: -PzetaSdkVersion=… overrides the catalog default. `latest` (or any locally
// publishToMavenLocal'd tag) resolves from the mavenLocal block in the common conventions
// plugin; numeric releases resolve from Maven Central.
val zetaSdkVersion: String =
    providers.gradleProperty("zetaSdkVersion").orElse(libs.versions.zeta.sdk).get()

dependencies {
    implementation(project(":connector"))
    implementation("de.gematik.zeta:zeta-sdk-jvm:$zetaSdkVersion") {
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

tasks.named<Tar>("distTar") {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}
