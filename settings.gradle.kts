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

include(":core:common")
include(":core:model")
include(":core:timeseries")
include(":feature:metrics")
include(":feature:protocol")
include(":feature:assessment")
include(":engine:sensor")
include(":core:datastore")
include(":core:database")
include(":engine:vision")
include(":engine:render")
include(":feature:report")
