// :core:model — pure Kotlin, the shared vocabulary (ARCHITECTURE.md).
// Depends on NOTHING: no Android SDK, no Matrix SDK, no coroutines-android.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.withType<Test> { useJUnitPlatform() }
