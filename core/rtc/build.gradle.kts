// :core:rtc — MatrixRTC voice-call signalling (docs/VOICE.md, ADR 0006).
// The one narrow home for call-protocol logic. It does NOT import the Rust SDK
// (spike 1 confirmed :core:matrix can expose the raw send/observe primitives, so
// the "only :core:matrix imports the SDK" rule stays intact); it builds
// m.call.member membership on top of MatrixSession. Audio transport (LiveKit) is
// an interface here with a stub impl until the SFU + lk-jwt-service exist.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "org.matchat.core.rtc"
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
    implementation(project(":core:matrix"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
    // LiveKit Android (bundles WebRTC natives) — the media transport for calls.
    // Largest single APK addition; release builds split per ABI (PLAN.md §4).
    implementation(libs.livekit.android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

tasks.withType<Test> { useJUnitPlatform() }
