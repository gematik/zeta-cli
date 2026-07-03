plugins {
    id("buildlogic.kotlin-application-conventions")
    kotlin("plugin.serialization")
}

// Mirrors :cli — `-PzetaSdkVersion=…` overrides the catalog default; `latest` resolves from
// mavenLocal, numeric releases from Maven Central.
val zetaSdkVersion: String =
    providers.gradleProperty("zetaSdkVersion").orElse(libs.versions.zeta.sdk).get()

dependencies {
    implementation("de.gematik.zeta:zeta-sdk-jvm:$zetaSdkVersion") {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    implementation(libs.clikt)
    implementation(libs.kotlin.logging)
    implementation(libs.logback.classic)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // Build an in-memory PKCS#12 per SMC-B card from its DER cert + PKCS#8 EC key.
    implementation(libs.bouncycastle.bcpkix)
    // Per-client SDK state + the 100k-card store live in one embedded SQLite file.
    implementation(libs.sqlite.jdbc)
    // Read the gzip+tar SMC-B bundles during `import-cards`.
    implementation(libs.commons.compress)
}

application {
    mainClass = "de.gematik.zeta.stress.MainKt"
    applicationName = "zeta-stress"
}
