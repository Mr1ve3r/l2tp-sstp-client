import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Server certificate store and trust policies (SPEC phase 5).
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jlleitschuh.gradle.ktlint")
    id("com.google.devtools.ksp")
    jacoco
}

android {
    namespace = "io.github.mr1ve3r.combined.core.trust"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The exported schema is the record of what shipped. Room reads it to generate
// migrations, and a diff under `schemas/` is the review signal that a release
// changes the on-device database.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// ktlint runs on the modules this fork adds, not on `:app`: those sources come
// from upstream TunnelForge, and reformatting them would bury real changes.
ktlint {
    android.set(true)
    ignoreFailures.set(false)
    filter {
        exclude { it.file.path.contains(File.separator + "build" + File.separator) }
    }
}

dependencies {
    api(project(":engine-api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.annotation)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.room.testing)
}

// SPEC 11.4 asks for coverage of this module at 80% or better, and this is
// where that number is produced and enforced.
//
// Only the JVM unit tests feed it. The instrumented tests need a device, CI has
// none, and a gate that silently passes because it never ran is worse than no
// gate -- so the classes that exist only to talk to Room or to the filesystem
// are excluded below rather than counted as covered. What remains is the part
// of this module that decides anything: the trust policies, the validator, the
// parser, the hostname check and the pre-flight.
jacoco {
    toolVersion = libs.versions.jacoco.get()
}

val coverageExclusions = listOf(
    // Room writes these from the DAO interfaces and the @Database class. They
    // are Room's code, not code this project can be wrong about.
    "**/*_Impl*.class",
    "**/*_Impl*",
    "**/store/TrustDatabase.class",
    "**/store/TrustDatabase$*.class",
    "**/store/ServerCertificateDao*.class",
    "**/profile/ProfileDao*.class",
    "**/profile/FailoverGroupDao*.class",

    // Facades over SQLite, the keystore and the filesystem. Every branch in
    // them is an I/O call, and `src/androidTest` is where they are covered --
    // on a device, because that is the only place those calls exist.
    "**/store/TrustStore.class",
    "**/store/TrustStore$*.class",
    "**/store/CertificateFileStore.class",
    "**/store/CertificateFileStore$*.class",
    "**/profile/ProfileStore.class",
    "**/profile/ProfileStore$*.class",
    "**/profile/ProfileSecrets.class",
    "**/profile/ProfileSecrets$*.class",
    "**/profile/FailoverGroupStore.class",
    "**/profile/FailoverGroupStore$*.class",

    // `org.json` is a stub in the JVM `android.jar` that throws from every
    // method, so this converter cannot run off a device at all. The
    // instrumented store tests exercise it. See its own KDoc.
    "**/store/StringListConverter.class",

    // Room entities and query result holders. Their bytecode is the data-class
    // members the compiler generates -- `equals`, `hashCode`, `toString`,
    // `copy`, `componentN` -- and counting those as untested logic buries the
    // classes that do decide something. Companions are NOT excluded: the
    // mapping and normalisation functions on them are hand-written and
    // `StoreMappingTest` covers them.
    "**/store/ServerCertificateEntity.class",
    "**/store/ProfileCertificateRef.class",
    "**/store/CertificateWithUsage.class",
    "**/store/StoredCertificate.class",
    "**/profile/VpnProfile.class",
    "**/profile/ProfileWithSecrets.class",
    "**/profile/PerAppMode.class",
    "**/profile/FailoverGroup.class",
    "**/profile/FailoverGroupMember.class",
    "**/profile/FailoverGroupWithMembers.class",
)

val jacocoTestReport = tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    description = "Coverage of the JVM unit tests for this module (SPEC 11.4)."
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
            exclude(coverageExclusions)
        },
    )
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(
        fileTree(layout.buildDirectory) { include("**/testDebugUnitTest.exec") },
    )
}

tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
    group = "verification"
    description = "Fails when this module drops below the SPEC 11.4 coverage floor."
    dependsOn(jacocoTestReport)

    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
            exclude(coverageExclusions)
        },
    )
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(
        fileTree(layout.buildDirectory) { include("**/testDebugUnitTest.exec") },
    )

    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}
