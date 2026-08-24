import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

// Protocol-agnostic TUN/routes/DNS layer (SPEC phase 3). Must never learn which protocol is running.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "io.github.mr1ve3r.combined.core.tunnel"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// ktlint is applied only to the modules introduced by this fork. The existing
// `:app` sources come from upstream TunnelForge and reformatting them wholesale
// would bury real changes in noise (SPEC rule 0.3.1).
ktlint {
    android.set(true)
    ignoreFailures.set(false)
    filter {
        exclude { it.file.path.contains(File.separator + "build" + File.separator) }
    }
}

dependencies {
    api(project(":engine-api"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
