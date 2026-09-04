import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Protocol-agnostic TUN/routes/DNS layer (SPEC phase 3).
// Must never learn which protocol is running.
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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
