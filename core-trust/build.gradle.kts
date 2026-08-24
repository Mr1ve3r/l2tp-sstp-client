import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Server certificate store and trust policies (SPEC phase 5).
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "io.github.mr1ve3r.combined.core.trust"
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.annotation)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
