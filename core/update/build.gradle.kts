// :core:update — in-app update check + APK download/install against GitHub
// Releases (Settings > Software update). Plain HttpURLConnection + org.json,
// same rationale as :core:rtc's HttpTokenService — no extra HTTP client. Reads
// the running version via PackageManager (BuildConfig is disabled app-wide,
// gradle.properties). Nothing here imports the Matrix SDK or any feature.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "org.matchat.core.update"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test> { useJUnitPlatform() }
