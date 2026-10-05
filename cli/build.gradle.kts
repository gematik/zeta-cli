import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.tasks.application.CreateStartScripts
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
    // so EC client certs survive the mTLS handshake (Konnektor `.kon` self-signed certs
    // are ECDSA in modern setups; Ktor CIO's TLS hard-codes RSA/DSS and drops EC
    // entirely). Also exposes `proxyAuthenticator` for HTTP-CONNECT proxy auth (Ktor
    // CIO doesn't preemptively send `Proxy-Authorization`, which presents as
    // `SocketException: Connection reset` against authenticating corporate proxies).
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.logging)
    // `zeta serve` — the local daemon serves plaintext HTTP over a unix socket. CIO *server* is
    // unaffected by the CIO *client* TLS limitation noted above (no TLS is terminated here); all
    // outbound calls still go through the OkHttp client.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    // Already on the runtime classpath via mordant-jvm-jna; declared here so we can call
    // isatty(2) directly to detect whether stderr is a TTY (see term/StderrColors.kt).
    implementation(libs.jna)
    // YAML parser backing the config-file value source (./zeta.yaml and the XDG fallback),
    // and the `zeta stress run` rate-waveform profiles.
    implementation(libs.snakeyaml)
    // `zeta stress`: build an in-memory PKCS#12 per SMC-B card from its DER cert + PKCS#8 EC key,
    // hold the 100k-card corpus + per-client SDK state in one embedded SQLite file, and read the
    // gzip+tar SMC-B bundles during `import-cards`.
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.sqlite.jdbc)
    implementation(libs.commons.compress)
    // `zeta probe` pushes metrics and traces via OTLP. The JDK HttpClient sender replaces the OkHttp one,
    // which targets OkHttp 4 while Ktor pulls OkHttp 5. Autoconfigure makes every OTEL_* env var work.
    implementation(platform(libs.opentelemetry.bom))
    implementation(libs.opentelemetry.sdk.autoconfigure)
    implementation(libs.opentelemetry.exporter.otlp) {
        exclude(group = "io.opentelemetry", module = "opentelemetry-exporter-sender-okhttp")
    }
    implementation(libs.opentelemetry.exporter.sender.jdk)
    // MockEngine for testing the service-discovery catalog client without a real HTTP call.
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.opentelemetry.sdk.testing)
}

application {
    mainClass = "de.gematik.zeta.cli.MainKt"
    applicationName = "zeta"
    // `zeta popp standard` uses javax.smartcardio (PC/SC), whose module java.smartcardio is not part
    // of java.se and so isn't resolved by default for a classpath app — pull it in explicitly.
    // kotlin-logging 8 announces its logger factory on stdout at first use, which would corrupt
    // output that scripts pipe from `zeta`.
    applicationDefaultJvmArgs = listOf(
        "--add-modules", "java.smartcardio",
        "-Dkotlin-logging.logStartupMessage=false",
    )
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
    // Resolve the java.smartcardio JDK module at compile time (see applicationDefaultJvmArgs).
    compilerOptions {
        freeCompilerArgs.add("-Xadd-modules=java.smartcardio")
    }
}

tasks.named<Tar>("distTar") {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}

// Prepend `chcp 65001` to the Windows launcher so the console renders UTF-8 correctly.
// Without this, the JVM writes UTF-8 bytes to stderr but legacy Windows code pages
// (CP-850 / CP-1252) read them as mojibake — `—` shows as `ÔÇö`, `─` as `ÔöÇ`, etc.
// macOS / Linux launchers are unaffected.
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        val bat = windowsScript
        bat.writeText("@chcp 65001 >NUL\r\n" + bat.readText())
    }
}

// cmd caps a line at 8191 characters after %APP_HOME% expansion, and the generated `set CLASSPATH=`
// line spells out every jar, so a long install path broke the Windows launcher. A manifest-only jar
// lists them instead (relative to itself, in Gradle's order), the launcher needs a single entry, and
// jars left over from an older install in lib/ are never loaded. The Unix script has no such limit.
val classpathJar by tasks.registering(Jar::class) {
    archiveFileName.set("zeta-classpath.jar")
    destinationDirectory.set(layout.buildDirectory.dir("classpath-jar"))
    val runtimeFiles: FileCollection = configurations.runtimeClasspath.get()
    val mainJarName = tasks.jar.flatMap { it.archiveFileName }
    inputs.files(runtimeFiles)
    inputs.property("mainJar", mainJarName)
    doFirst {
        manifest.attributes["Class-Path"] = (listOf(mainJarName.get()) + runtimeFiles.files.map { it.name }).joinToString(" ")
    }
}

distributions.named("main") {
    contents { from(classpathJar) { into("lib") } }
}

tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        val bat = windowsScript
        val pathing = "set CLASSPATH=%APP_HOME%\\lib\\zeta-classpath.jar"
        val rewritten = bat.readText().replace(Regex("(?m)^set CLASSPATH=.*$"), Regex.escapeReplacement(pathing))
        check(pathing in rewritten && rewritten.lines().none { it.startsWith("set CLASSPATH=") && it != pathing }) {
            "zeta.bat no longer has the expected 'set CLASSPATH=' line; the Windows launcher would exceed cmd's line limit"
        }
        bat.writeText(rewritten)
    }
}

// The runtime must stay free of Prometheus and of alpha OpenTelemetry artifacts: `zeta probe` pushes via
// OTLP only, and alpha artifacts carry no compatibility guarantee. Fails `check` with the dependency path.
val checkRuntimeClasspath by tasks.registering {
    group = "verification"
    description = "Fail if the runtime classpath contains Prometheus or alpha OpenTelemetry modules."
    val root = configurations.runtimeClasspath.flatMap { it.incoming.resolutionResult.rootComponent }
    doLast {
        fun forbidden(group: String, name: String, version: String) =
            group == "io.prometheus" || "prometheus" in name ||
                (group == "io.opentelemetry" && version.endsWith("-alpha"))

        val paths = mutableMapOf<ResolvedComponentResult, List<String>>()
        val queue = ArrayDeque<ResolvedComponentResult>()
        val start = root.get()
        paths[start] = emptyList()
        queue.add(start)
        val offending = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { dep ->
                val next = dep.selected
                if (next in paths) return@forEach
                val id = next.moduleVersion ?: return@forEach
                val coords = "${id.group}:${id.name}:${id.version}"
                paths[next] = paths.getValue(component) + coords
                if (forbidden(id.group, id.name, id.version)) offending += paths.getValue(next).joinToString(" -> ")
                queue.add(next)
            }
        }
        if (offending.isNotEmpty()) {
            throw GradleException(
                "runtimeClasspath contains forbidden modules (Prometheus or alpha OpenTelemetry):\n" +
                    offending.joinToString("\n") { "  $it" },
            )
        }
    }
}

tasks.named("check") { dependsOn(checkRuntimeClasspath) }
