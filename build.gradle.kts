import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip

allprojects {
    group = rootProject.group
    version = rootProject.version
}

// The release ships two entry points from one archive: `bin/zeta` (the CLI) and
// `bin/zeta-stress` (the load-test tool). Each module produces its own application image via
// the `application` plugin; we merge the two `installDist` trees into a single layout, sharing
// one `lib/` (the ~86 common jars are deduplicated by filename).
//
// ```
// zeta-<v>/
// ├── bin/zeta          # CLI launcher
// ├── bin/zeta-stress   # stress-test launcher
// └── lib/              # every jar both entry points need (shared, deduped)
// ```

val distDirName = "zeta-${project.version}"
val distRoot = layout.buildDirectory.dir("dist/$distDirName")

val assembleDist by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Merge the zeta + zeta-stress application images into one release tree."
    dependsOn(":cli:installDist", ":stress:installDist")
    // Same jar (identical filename + version, one source of truth in the catalog) appears in
    // both images; keep the first copy.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(project(":cli").layout.buildDirectory.dir("install/zeta"))
    from(project(":stress").layout.buildDirectory.dir("install/zeta-stress"))
    into(distRoot)
}

// Gradle's archive tasks default every entry to 0644; the bin/ scripts need +x after extract,
// so bin/ is added separately with 0755.
fun CopySpec.distContents() {
    from(distRoot.map { it.dir("bin") }) {
        into("$distDirName/bin")
        filePermissions { unix("0755") }
    }
    from(distRoot) {
        into(distDirName)
        exclude("bin/**")
    }
}

val distTar by tasks.registering(Tar::class) {
    group = "distribution"
    description = "Pack the merged distribution into a gzip tarball."
    dependsOn(assembleDist)
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
    archiveFileName.set("$distDirName.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    distContents()
}

val distZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Pack the merged distribution into a zip."
    dependsOn(assembleDist)
    archiveFileName.set("$distDirName.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    distContents()
}
