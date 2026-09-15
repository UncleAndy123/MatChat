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
        // LiveKit's audioswitch dependency is published only on JitPack (as a
        // git-commit version). Scope JitPack to that group so nothing else
        // resolves through it.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.davidliu") }
        }
    }
}

rootProject.name = "MatChat"

include(":app")

include(":core:model")
include(":core:matrix")
include(":core:ui")
include(":core:policy")
include(":core:contacts")
include(":core:rtc")
include(":core:testing")

include(":feature:onboarding")
include(":feature:roomlist")
include(":feature:timeline")
include(":feature:invites")
include(":feature:newchat")
include(":feature:settings")
include(":feature:verification")
include(":feature:call")
