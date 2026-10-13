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
    // Apply the org.jetbrains.kotlin.jvm Plugin to add support for Kotlin.
    id("org.jetbrains.kotlin.jvm")
}

repositories {
    // Local-only for ZETA SDK snapshots ("to test latest ZETA"). Scoped to the gematik
    // group so KMP root modules (e.g. okhttp:5.3.2) — whose `okhttp-jvm` redirect lives
    // in Gradle module metadata that mavenLocal doesn't read — keep resolving from
    // Central where the redirect works. An unscoped mavenLocal pulls the empty
    // okhttp-5.3.2.jar stub onto the classpath and breaks `OkHttpClient.Builder`.
    //
    // Pin to POM-only metadata: zeta-sdk is published via `publishJvmPublicationToMavenLocal`
    // which only deposits the `*-jvm` artifacts. The Gradle module metadata in those JARs
    // still references KMP root modules (`flow-controller`, not `flow-controller-jvm`), so
    // letting Gradle read it would chase missing roots. The POM lists the `-jvm` deps
    // directly, which is exactly what we have on disk.
    mavenLocal {
        content { includeGroupByRegex("de\\.gematik\\..*") }
        metadataSources {
            mavenPom()
            ignoreGradleMetadataRedirection()
        }
    }
    mavenCentral()
}

dependencies {
    constraints {
        // Define dependency versions as constraints
        implementation("org.apache.commons:commons-text:1.15.0")
    }

    // Use JUnit Jupiter for testing.
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
    systemProperty("kotlin-logging.logStartupMessage", "false")
}
