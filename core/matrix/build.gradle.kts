// :core:matrix — the ONLY module allowed to import org.matrix.rustcomponents.*
// (AGENTS.md §0, enforced by the ArchitectureTest Konsist rule). In M0 it holds
// the public contract plus an in-memory stub so the app runs with empty screens;
// the SDK dependency (libs.matrix.rustsdk) and the real mappers land in M1.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "org.matchat.core.matrix"
    compileSdk = 35

    defaultConfig { minSdk = 24 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:policy")) // to tag invites allowedByPolicy at the boundary

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // M1: the Matrix Rust SDK. This is the ONLY module that depends on it.
    implementation(libs.matrix.rustsdk)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

tasks.withType<Test> { useJUnitPlatform() }
