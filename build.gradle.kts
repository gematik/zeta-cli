import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip

allprojects {
    group = rootProject.group
    version = rootProject.version
}

// The release ships a single entry point, `bin/zeta`, from one archive. The CLI (which now hosts
// the `zeta stress` load-test commands too) produces its application image via the `application`
// plugin; we stage it under a `zeta-<v>/` top-level dir so the tarball extracts cleanly.
//
// ```
// zeta-<v>/
// ├── bin/zeta   # single launcher
// └── lib/       # every jar the CLI needs
// ```

val distDirName = "zeta-${project.version}"
val distRoot = layout.buildDirectory.dir("dist/$distDirName")

val assembleDist by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stage the zeta application image into the release tree."
    dependsOn(":cli:installDist")
    from(project(":cli").layout.buildDirectory.dir("install/zeta"))
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
