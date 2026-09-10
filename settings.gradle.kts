pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
    buildscript {
        repositories { google(); mavenCentral() }
        dependencies { classpath("com.android.tools:r8:8.13.19") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "PARS-Plugins"

// Any top-level folder with build.gradle.kts becomes a module automatically.
// Template/demo modules are deliberately excluded from the production repository.
val excludedModules = setOf(
    "ExampleProvider"
)

rootDir.listFiles()
    ?.filter {
        it.isDirectory &&
            File(it, "build.gradle.kts").exists() &&
            it.name !in excludedModules
    }
    ?.sortedBy { it.name.lowercase() }
    ?.forEach { include(":${it.name}") }
