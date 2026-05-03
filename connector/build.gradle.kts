plugins {
    id("buildlogic.kotlin-library-conventions")
    kotlin("plugin.serialization")
}

dependencies {
    api(libs.ktor.client.core)
    implementation(libs.kotlin.logging)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xmlutil.serialization)
    // Used to parse the ISIS-MTT admission extension (1.3.36.8.3.3) from SMC-B C.AUT
    // certs to extract the Telematik-ID. The Zeta SDK pulls BC transitively via
    // de.gematik.zeta:crypto, so this is on the classpath at runtime regardless — declared
    // here to make the compile-time intent explicit.
    implementation(libs.bouncycastle.bcpkix)

    // OkHttp is the only supported engine: it routes through JSSE, which sends EC client
    // certs in mTLS handshakes (gematik KSP / HBA / SMC-B cards use brainpool ECC keys).
    // Ktor CIO's TLS stack hard-codes RSA / DSS only — see CertificateType.kt in CIO source.
    // compileOnly so consumers that bring their own pre-configured HttpClient don't pull
    // OkHttp at runtime; pull `ktor-client-okhttp` explicitly to use the bridge.
    compileOnly(libs.ktor.client.okhttp)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.okhttp)
}
