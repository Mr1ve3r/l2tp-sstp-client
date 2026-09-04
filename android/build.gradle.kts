allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
// Flutter's generated boilerplate makes every subproject wait for `:app` to be
// evaluated. That is fine for Flutter plugin modules, but `:app` depends on the
// engine and core modules, so forcing them to evaluate after `:app` would be a
// circular evaluation dependency. Exclude them.
val standaloneModules =
    setOf("engine-api", "engine-l2tp", "engine-sstp", "core-tunnel", "core-trust")

subprojects {
    if (project.name !in standaloneModules) {
        project.evaluationDependsOn(":app")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
