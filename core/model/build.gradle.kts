// :core:model — pure Kotlin, the shared vocabulary (ARCHITECTURE.md).
// Depends on NOTHING: no Android SDK, no Matrix SDK, no coroutines-android.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Target JVM 17 bytecode on whatever JDK (>= 17) runs Gradle — no toolchain, so
// no separate JDK 17 install is required. Both compileJava and compileKotlin are
// pinned to 17 so their targets can never drift ("Inconsistent JVM-target").
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.withType<Test> { useJUnitPlatform() }
