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
            mapOf("android" to "true"),
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
}

tasks.register("detektAll") {
    group = "verification"
    description = "Runs detekt on every module."
    dependsOn(subprojects.map { "${it.path}:detekt" })
}
