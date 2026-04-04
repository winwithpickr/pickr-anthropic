pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "anthropic"

// Use local engine checkout if available, otherwise fall back to published Maven artifact.
// Skip if consumed as a composite build (parent already provides engine).
fun tryIncludeBuild(path: String) {
    val dir = rootProject.projectDir.resolve("../$path")
    if (dir.exists()) {
        logger.lifecycle("pickr-anthropic: using local composite build for $path")
        includeBuild(dir)
    }
}

// Only include engine when building standalone (not as part of server composite build).
// When consumed via server's settings.gradle.kts, engine is already provided by the root.
if (gradle.parent == null) {
    tryIncludeBuild("engine")
}
