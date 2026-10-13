/*
 * Copyright (Change Date see Readme), gematik GmbH
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the
 * European Commission – subsequent versions of the EUPL (the "Licence").
 * You may not use this work except in compliance with the Licence.
 *
 * You find a copy of the Licence in the "Licence" file or at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.
 * In case of changes by gematik GmbH find details in the "Readme" file.
 *
 * See the Licence for the specific language governing permissions and limitations under the Licence.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 */
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip

plugins {
    base
    alias(libs.plugins.spotless)
}

// One root-level Spotless covers every module and buildSrc, so `check` fails on a missing
// OSPO license header anywhere in the tree. The SOAP stubs under connector/api are generated
// (wsdl2openapi, DO NOT EDIT) and carry no header.
spotless {
    val header = rootProject.file("LICENSE_PLACEHOLDER")
    kotlin {
        target("cli/src/**/*.kt", "connector/src/**/*.kt", "buildSrc/src/**/*.kt")
        targetExclude("connector/src/main/kotlin/de/gematik/connector/api/**")
        licenseHeaderFile(header)
    }
    kotlinGradle {
        target("*.gradle.kts", "cli/*.gradle.kts", "connector/*.gradle.kts", "buildSrc/*.gradle.kts", "buildSrc/src/**/*.gradle.kts")
        licenseHeaderFile(header, "(import|plugins|dependencyResolutionManagement)\\b")
    }
}

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
