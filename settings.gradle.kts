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
        // Opt-in only (default off): consume the locally-built patched sdk-android
        // AAR from tools/matrix-shim (docs/VOICE.md §4.1). Off by default so the
        // normal build resolves exactly as before. Reads from the project-local
        // repo (drop the CI artifact into tools/matrix-shim/local-maven) and also
        // mavenLocal (for local build-aar.sh runs); both scoped to just the SDK.
        if (providers.gradleProperty("matchat.useShimSdk").orNull.toBoolean()) {
            maven {
                url = File(rootDir, "tools/matrix-shim/local-maven").toURI()
                content { includeModule("org.matrix.rustcomponents", "sdk-android") }
            }
            mavenLocal {
                content { includeModule("org.matrix.rustcomponents", "sdk-android") }
            }
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
include(":core:update")
include(":core:testing")

include(":feature:onboarding")
include(":feature:roomlist")
include(":feature:timeline")
include(":feature:invites")
include(":feature:newchat")
include(":feature:settings")
include(":feature:verification")
include(":feature:call")
