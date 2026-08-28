// Google's Maven Central mirror sits ahead of mavenCentral(): Maven Central
// answers 429 "Too Many Requests" under Gradle's parallel resolution from this
// network, which failed the build outright. The mirror serves the same
// artifacts and is not rate-limited; mavenCentral() stays as the fallback.
// The URL is repeated because pluginManagement is evaluated in its own scope,
// before any top-level declaration in this file exists.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
    }
}
rootProject.name = "TrellisStudio"
include(":app")
