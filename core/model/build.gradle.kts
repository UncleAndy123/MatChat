// :core:model — pure Kotlin, the shared vocabulary (ARCHITECTURE.md).
// Depends on NOTHING: no Android SDK, no Matrix SDK, no coroutines-android.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// One toolchain drives both compileJava and compileKotlin to JVM 17, so their
// targets can never drift (the "Inconsistent JVM-target" error).
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.withType<Test> { useJUnitPlatform() }
