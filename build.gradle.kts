// Root build script. Module plugins are applied per-module; here we only
// register the classpath-less plugin aliases and the repo-wide quality tasks.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.navigation.safeargs) apply false
    alias(libs.plugins.paparazzi) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
}

// --- Spotless: formatting is never a review comment (PLAN.md §8.4). ---
spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**/*.kt")
        ktlint().editorConfigOverride(
            mapOf("android" to "true")
        )
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        ktlint()
    }
}

// --- Detekt: static analysis on every module (PLAN.md §8.4). ---
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(files("$rootDir/config/detekt.yml"))
        parallel = true
    }

    // Paparazzi screenshot tests (*ScreenshotTest) render layouts through
    // layoutlib and are meant to run via record/verifyPaparazzi against committed
    // golden images — not as part of the plain unit-test gate (there are no
    // goldens yet, and some layouts hit a layoutlib theme-attr limitation). Keep
    // `test` a fast, deterministic unit gate by excluding them from every test
    // invocation EXCEPT a Paparazzi one (record/verifyPaparazzi still run them).
    // Re-enable in the gate once goldens are recorded and a verify job is wired.
    tasks.withType<Test>().configureEach {
        val paparazziRun = gradle.startParameter.taskNames.any {
            it.contains("Paparazzi", ignoreCase = true)
        }
        if (!paparazziRun) {
            exclude("**/*ScreenshotTest*")
        }
    }
}

tasks.register("detektAll") {
    group = "verification"
    description = "Runs detekt on every module."
    dependsOn(subprojects.map { "${it.path}:detekt" })
}

// --- matrix-shim opt-in (docs/VOICE.md §4.1, tools/matrix-shim) ---
// When -Pmatchat.useShimSdk=true, resolve org.matrix.rustcomponents:sdk-android
// to our locally-built patched AAR instead of the upstream version. Off by
// default: the block is skipped entirely, so the normal build is unchanged.
if (providers.gradleProperty("matchat.useShimSdk").orNull.toBoolean()) {
    val shimVersion = providers.gradleProperty("matchat.shimSdkVersion").orNull
        ?: error("matchat.useShimSdk=true requires matchat.shimSdkVersion (e.g. 26.09.3-matchat-shim1)")
    allprojects {
        configurations.configureEach {
            resolutionStrategy.eachDependency {
                if (requested.group == "org.matrix.rustcomponents" && requested.name == "sdk-android") {
                    useVersion(shimVersion)
                    because("matchat.useShimSdk: patched to-device build from tools/matrix-shim")
                }
            }
        }
    }
}
