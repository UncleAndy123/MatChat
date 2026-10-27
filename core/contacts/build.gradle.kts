// :core:contacts — people the user already knows (admin-pushed + local + recents).
// Not a directory: no query method, no search — the list is short by construction
// (AGENTS.md §0, ARCHITECTURE.md).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "org.matchat.core.contacts"
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
    implementation(project(":core:policy"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.core)
    // ContextCompat.checkSelfPermission for the optional phone-contacts copy.
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.json) // real org.json for the ContactsCodec unit test
}

tasks.withType<Test> { useJUnitPlatform() }
