plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "zeta-cli"
include("connector", "cli", "cli-sdk1_0", "launcher")
