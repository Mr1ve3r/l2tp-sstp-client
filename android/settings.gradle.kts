pluginManagement {
    val flutterSdkPath =
        run {
            val properties = java.util.Properties()
            file("local.properties").inputStream().use { properties.load(it) }
            val flutterSdkPath = properties.getProperty("flutter.sdk")
            require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
            flutterSdkPath
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Versions here are mirrored in gradle/libs.versions.toml. Gradle cannot read a
// version catalog from this block, so the two must be kept in sync by hand.
plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application") version "8.11.1" apply false
    id("com.android.library") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false
    id("com.google.devtools.ksp") version "2.2.20-2.0.4" apply false
}

include(":app")

// The engine and core modules live at the repository root (SPEC phase 1.1), one
// level above this Gradle build, so each needs an explicit projectDir.
listOf(
    "engine-api",
    "engine-l2tp",
    "engine-sstp",
    "core-tunnel",
    "core-trust",
).forEach { module ->
    include(":$module")
    project(":$module").projectDir = rootDir.parentFile.resolve(module)
}
