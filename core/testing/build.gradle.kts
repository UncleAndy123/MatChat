// :core:testing — fakes only, consumed by other modules' tests (AGENTS.md §2).
// An Android library because the fakes implement contracts that reference
// :core:policy (an Android module). Test dependencies are `api` so a feature's
// unit test gets Turbine and coroutines-test transitively.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.matchat.core.testing"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:matrix"))
    api(project(":core:policy"))
    api(project(":core:contacts"))

    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.test)
    api(libs.turbine)
    api(libs.junit.jupiter.api)
}
