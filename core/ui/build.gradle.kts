// :core:ui — the device abstraction: the single KeyMap, SoftkeyFragment, the
// focus engine, the one menu construct, the theme and focus_selector. The ONLY
// module allowed custom View subclasses or key handling (ARCHITECTURE.md).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.paparazzi)
}

android {
    namespace = "org.matchat.core.ui"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    buildFeatures { viewBinding = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core:model"))

    api(libs.androidx.core.ktx)
    api(libs.androidx.appcompat)
    api(libs.androidx.fragment.ktx)
    api(libs.androidx.constraintlayout)
    api(libs.androidx.recyclerview)

    testImplementation(libs.junit4)
    testImplementation(libs.androidx.test.ext.junit)
}
