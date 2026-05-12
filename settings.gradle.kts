plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "zeta-cli"
include("connector", "cli")
/*
includeBuild("../zeta-sdk") {
    dependencySubstitution {
        substitute(module("de.gematik.zeta:zeta-sdk")).using(project(":zeta-sdk"))
    }
}
 */
