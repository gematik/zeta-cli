plugins {
    id("buildlogic.kotlin-library-conventions")
    kotlin("plugin.serialization")
}

dependencies {
    api(libs.zeta.sdk) {
        // zeta-sdk transitively pulls slf4j-simple, which clashes with our Logback binding.
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    api(libs.ktor.client.core)
    implementation(libs.kotlin.logging)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.ktor.client.mock)
}
