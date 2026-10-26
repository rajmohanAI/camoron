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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Camoron"

include(":app")
include(":core:common")
include(":core:designsystem")
include(":core:imaging")
include(":domain")
include(":data:camera")
include(":data:media")
include(":data:processing")
include(":data:settings")
include(":feature:onboarding")
include(":feature:camera")
include(":feature:settings")
include(":feature:editor")
