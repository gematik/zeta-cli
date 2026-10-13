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
    // certs in mTLS handshakes. Konnektor self-signed `.kon` client certs are typically
    // ECDSA (NIST, rarely brainpool); Ktor CIO's TLS stack hard-codes RSA / DSS only and
    // silently drops ALL EC certs — see CertificateType.kt in CIO source.
    // compileOnly so consumers that bring their own pre-configured HttpClient don't pull
    // OkHttp at runtime; pull `ktor-client-okhttp` explicitly to use the bridge.
    compileOnly(libs.ktor.client.okhttp)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.okhttp)
}
