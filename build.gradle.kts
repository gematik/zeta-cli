import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.bundling.Compression

allprojects {
    group = rootProject.group
    version = rootProject.version
}

// Repositories — mirrors `buildSrc/.../buildlogic.kotlin-common-conventions.gradle.kts`
// so the detached SDK-version configurations used by `assembleDist` below can resolve
// `de.gematik.zeta:*` from mavenLocal and everything else from Central. Scoped so the
// global mavenLocal doesn't shadow Maven Central artifacts.
repositories {
    mavenLocal {
        content { includeGroupByRegex("de\\.gematik\\..*") }
        metadataSources {
            mavenPom()
            ignoreGradleMetadataRedirection()
        }
    }
    mavenCentral()
}

// Bundled SDK versions for the multi-SDK `assembleDist`. The cli compiles against a
// single SDK (catalog default); at runtime the launcher picks from these.
val bundledSdkVersions: List<String> =
    libs.versions.zeta.sdk.bundled.get().split(',').map { it.trim() }.filter { it.isNotEmpty() }
val defaultSdkVersion: String =
    bundledSdkVersions.lastOrNull() ?: error("zeta-sdk-bundled is empty in libs.versions.toml")

val distDirName = "zeta-${project.version}"
val distRoot = layout.buildDirectory.dir("dist/$distDirName")

/**
 * Layout the multi-SDK distribution: launcher bin + jar, shared cli/connector + deps,
 * one `lib-sdk-<v>/` per bundled SDK version, and a single-line `lib-sdk/default` text
 * file the launcher reads. Shared deps live in `lib-cli/` exactly once; `lib-sdk-<v>/`
 * dirs only carry jars unique to that SDK version (matched by basename subtraction).
 *
 * All dependency resolution and Task→File extraction happens at configuration time so
 * the doLast block holds nothing but plain File references — required by the Gradle
 * configuration cache.
 */
val assembleDist by tasks.registering {
    group = "distribution"
    description = "Assemble the multi-SDK zeta distribution layout."

    dependsOn(
        project(":launcher").tasks.named("installDist"),
        project(":cli").tasks.named("jar"),
        project(":connector").tasks.named("jar"),
    )

    // Eagerly resolve everything we need so doLast holds only File / String / Map values.
    val launcherInstallDir = project(":launcher").layout.buildDirectory.dir("install/zeta").get().asFile
    val cliJarFile = (project(":cli").tasks.named("jar").get() as org.gradle.jvm.tasks.Jar)
        .archiveFile.get().asFile
    val connectorJarFile = (project(":connector").tasks.named("jar").get() as org.gradle.jvm.tasks.Jar)
        .archiveFile.get().asFile
    val cliRuntime = project(":cli").configurations.getByName("runtimeClasspath")
    val nonSdkRuntimeJars: List<File> = cliRuntime.incoming.artifacts.artifacts.mapNotNull { artifact ->
        val id = artifact.id.componentIdentifier
        if (id is ModuleComponentIdentifier && id.group == "de.gematik.zeta") null
        else artifact.file
    }
    val sdkResolved: Map<String, Set<File>> = bundledSdkVersions.associateWith { version ->
        val conf = configurations.detachedConfiguration(
            dependencies.create("de.gematik.zeta:zeta-sdk-jvm:$version").apply {
                (this as ExternalModuleDependency)
                    .exclude(mapOf("group" to "org.slf4j", "module" to "slf4j-simple"))
            }
        )
        conf.resolve()
    }

    inputs.files(cliJarFile, connectorJarFile)
    inputs.files(nonSdkRuntimeJars)
    sdkResolved.values.forEach { inputs.files(it) }
    outputs.dir(distRoot)

    val versions = bundledSdkVersions
    val defaultVersion = defaultSdkVersion
    val outDirProvider = distRoot

    doLast {
        val outDir = outDirProvider.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()

        val binDir = File(outDir, "bin")
        File(launcherInstallDir, "bin").copyRecursively(binDir)
        // copyRecursively drops POSIX permissions — restore exec bit on the start-script.
        File(binDir, "zeta").setExecutable(true, false)
        File(launcherInstallDir, "lib").copyRecursively(File(outDir, "lib-launcher"))

        // Gradle's CreateStartScripts hardcodes the lib dir name. We moved the launcher
        // jars into `lib-launcher/` to keep the dist root tidy, so the script needs to
        // be rewritten to find them. POSIX script uses `APP_HOME/lib/`, Windows uses
        // `%APP_HOME%\lib\`.
        File(binDir, "zeta").let { f ->
            f.writeText(f.readText().replace("\$APP_HOME/lib/", "\$APP_HOME/lib-launcher/"))
        }
        File(binDir, "zeta.bat").let { f ->
            f.writeText(f.readText().replace("%APP_HOME%\\lib\\", "%APP_HOME%\\lib-launcher\\"))
        }

        val libCli = File(outDir, "lib-cli").apply { mkdirs() }
        sequenceOf(cliJarFile, connectorJarFile).plus(nonSdkRuntimeJars)
            .filter { it.isFile }
            .forEach { it.copyTo(File(libCli, it.name), overwrite = true) }

        val libCliFileNames = libCli.listFiles().orEmpty().map { it.name }.toSet()
        versions.forEach { version ->
            val libSdk = File(outDir, "lib-sdk-$version").apply { mkdirs() }
            sdkResolved[version]!!
                .asSequence()
                .filter { it.isFile && it.name !in libCliFileNames }
                .forEach { it.copyTo(File(libSdk, it.name), overwrite = true) }
        }

        File(outDir, "lib-sdk").apply { mkdirs() }
            .resolve("default")
            .writeText(defaultVersion + "\n")
    }
}

/** Tarball matching the cli's prior `:cli:distTar` layout: gzip, single top-level dir. */
val distTar by tasks.registering(Tar::class) {
    group = "distribution"
    description = "Pack the assembled multi-SDK distribution into a release tarball."
    dependsOn(assembleDist)
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
    archiveFileName.set("$distDirName.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    // Gradle's Tar defaults to 0644 for all entries — the bin/zeta launcher needs +x or
    // the user can't run it after extracting. Split bin/ from the rest so only it picks
    // up the executable mode.
    from(distRoot.map { it.dir("bin") }) {
        into("$distDirName/bin")
        filePermissions { unix("0755") }
    }
    from(distRoot) {
        into(distDirName)
        exclude("bin/**")
    }
}
