pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "messages"
include(":app")

// The baseline profile generator needs a device and tooling that trails AGP, so it is only part of
// the build when asked for: ./gradlew -PbaselineProfile :baselineprofile:connectedAndroidTest
if (providers.gradleProperty("baselineProfile").isPresent) {
    include(":baselineprofile")
}
 