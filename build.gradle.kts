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

// Each bundled SDK version is paired with the cli build that compiles against it. Versions
// come from the catalog pins the modules themselves build with, so there's one source of
// truth. The last entry is the launcher default. Adding an SDK means: new catalog pin +
// new `:cli-sdkX_Y` module + entry here.
val cliModuleBySdkVersion: Map<String, String> = linkedMapOf(
    libs.versions.zeta.sdk.legacy.get() to ":cli-sdk1_0",
    libs.versions.zeta.sdk.asProvider().get() to ":cli",
)

val bundledSdkVersions: List<String> = cliModuleBySdkVersion.keys.toList()
val defaultSdkVersion: String = bundledSdkVersions.last()

val distDirName = "zeta-${project.version}"
val distRoot = layout.buildDirectory.dir("dist/$distDirName")

/**
 * Multi-SDK distribution layout. Two SDK versions can't share a single cli jar because
 * the Kotlin compiler bakes synthetic-default-arg signatures into bytecode at compile
 * time, and those signatures move between SDK releases. Each bundled SDK pairs with a
 * cli jar compiled against it.
 *
 * ```
 * zeta-<v>/
 * ├── bin/zeta
 * ├── lib-launcher/      # launcher jars (no SDK refs)
 * ├── lib-cli-shared/    # connector + every non-SDK runtime dep (clikt, ktor, …)
 * ├── lib-cli-<v>/       # per-SDK cli jar (one per bundled SDK)
 * ├── lib-sdk-<v>/       # SDK jars unique to that version (basename-subtracted)
 * └── lib-sdk/default    # single-line text file with the default SDK version
 * ```
 *
 * The launcher merges lib-cli-shared, lib-cli-<chosen>, lib-sdk-<chosen> onto one
 * URLClassLoader. SDK transitives present in `lib-cli-shared/` get subtracted from
 * the per-SDK dirs by basename so we ship them only once.
 *
 * All dependency resolution and Task→File extraction happens at configuration time so
 * the doLast block holds nothing but plain File references — required by the Gradle
 * configuration cache.
 */
val assembleDist by tasks.registering {
    group = "distribution"
    description = "Assemble the multi-SDK zeta distribution layout."

    val launcherProj = project(":launcher")
    val connectorProj = project(":connector")
    val cliProjects: Map<String, Project> = cliModuleBySdkVersion.mapValues { (_, path) -> project(path) }

    dependsOn(
        launcherProj.tasks.named("installDist"),
        connectorProj.tasks.named("jar"),
    )
    cliProjects.values.forEach { dependsOn(it.tasks.named("jar")) }

    val launcherInstallDir = launcherProj.layout.buildDirectory.dir("install/zeta").get().asFile
    val connectorJarFile = (connectorProj.tasks.named("jar").get() as org.gradle.jvm.tasks.Jar)
        .archiveFile.get().asFile

    // Per-SDK cli jar. Each :cli-* module emits its own archive; we keep them in separate
    // lib-cli-<v>/ dirs so the same class name (de.gematik.zeta.cli.MainKt) with different
    // bytecode never collides.
    val cliJarBySdk: Map<String, File> = cliProjects.mapValues { (_, proj) ->
        (proj.tasks.named("jar").get() as org.gradle.jvm.tasks.Jar).archiveFile.get().asFile
    }

    // Shared deps = the cli's runtime classpath minus anything SDK-related. Anything from
    // group `de.gematik.zeta` is filtered out — those live in lib-sdk-<v>/ (and the cli jar
    // itself lives in lib-cli-<v>/).
    val cliRuntime = project(":cli").configurations.getByName("runtimeClasspath")
    val sharedRuntimeJars: List<File> = cliRuntime.incoming.artifacts.artifacts.mapNotNull { artifact ->
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

    inputs.files(connectorJarFile)
    inputs.files(sharedRuntimeJars)
    inputs.files(cliJarBySdk.values)
    inputs.dir(launcherProj.layout.buildDirectory.dir("install/zeta"))
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
        File(binDir, "zeta").setExecutable(true, false)
        File(launcherInstallDir, "lib").copyRecursively(File(outDir, "lib-launcher"))

        // Gradle's CreateStartScripts hardcodes `$APP_HOME/lib/` as the classpath dir.
        // We moved the launcher jars to `lib-launcher/` to keep the dist root tidy, so the
        // script needs the path rewritten.
        File(binDir, "zeta").let { f ->
            f.writeText(f.readText().replace("\$APP_HOME/lib/", "\$APP_HOME/lib-launcher/"))
        }
        File(binDir, "zeta.bat").let { f ->
            f.writeText(f.readText().replace("%APP_HOME%\\lib\\", "%APP_HOME%\\lib-launcher\\"))
        }

        val libCliShared = File(outDir, "lib-cli-shared").apply { mkdirs() }
        sequenceOf(connectorJarFile).plus(sharedRuntimeJars)
            .filter { it.isFile }
            .forEach { it.copyTo(File(libCliShared, it.name), overwrite = true) }

        val sharedFileNames = libCliShared.listFiles().orEmpty().map { it.name }.toSet()

        versions.forEach { version ->
            val libCli = File(outDir, "lib-cli-$version").apply { mkdirs() }
            cliJarBySdk[version]!!.copyTo(File(libCli, "cli.jar"), overwrite = true)

            val libSdk = File(outDir, "lib-sdk-$version").apply { mkdirs() }
            sdkResolved[version]!!
                .asSequence()
                .filter { it.isFile && it.name !in sharedFileNames }
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
