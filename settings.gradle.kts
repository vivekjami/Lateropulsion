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

rootProject.name = "lateropulsion"

include(":app")
include(":core:common")
include(":core:model")
include(":core:timeseries")
include(":core:database")
include(":core:datastore")
include(":engine:sensor")
include(":engine:vision")
include(":engine:render")
include(":feature:assessment")
include(":feature:protocol")
include(":feature:metrics")
include(":feature:report")
