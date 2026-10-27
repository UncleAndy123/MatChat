// :app — Application, Hilt graph, single Activity + nav host, the global key
// dispatcher, the sync foreground service, and the manifest. Nothing depends on
// :app (ARCHITECTURE.md). It wires the Navigator and hosts every feature.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.navigation.safeargs)
}

val appId: String = (project.findProperty("app.id") as String?) ?: "org.matchat.client"

android {
    namespace = "org.matchat.client"
    compileSdk = 35

    defaultConfig {
        applicationId = appId
        minSdk = 24
        targetSdk = 35
        versionCode = 10
        versionName = "0.2.1-M4"
        testInstrumentationRunner = "org.matchat.client.HiltTestRunner"
    }

    // Ship armeabi-v7a + arm64-v8a splits for RELEASE only; the Rust .so is the
    // bulk of the APK and the release size check fails a split over 25 MB
    // (PLAN.md §3, §8.4). Debug stays a single all-ABI APK so it installs on any
    // emulator (incl. x86_64) — there are no native libs until M1, so splitting a
    // debug build only makes it uninstallable on dev machines for no benefit.
    //
    // Release also emits a universal APK (all ABIs): it's what the in-app updater
    // downloads (:core:update / Settings > Software update), so one asset installs
    // on any device without the updater having to match ABIs. It's larger than a
    // split and is excluded from the 25 MB per-split gate below.
    val abiSplitEnabled = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
    splits {
        abi {
            isEnable = abiSplitEnabled
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = abiSplitEnabled
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    testOptions { unitTests.isReturnDefaultValues = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:matrix"))
    implementation(project(":core:policy"))
    implementation(project(":core:contacts"))
    implementation(project(":core:rtc"))
    implementation(project(":core:update"))

    // TLS: the Matrix SDK's native lib verifies homeserver certificates by calling
    // org.rustls.platformverifier.CertificateVerifier over JNI. That class is our
    // own source in this module (src/main/kotlin/org/rustls/platformverifier), not
    // a vendored jar — see docs/adr/0010 and its keep rule in proguard-rules.pro.

    implementation(project(":feature:onboarding"))
    implementation(project(":feature:roomlist"))
    implementation(project(":feature:timeline"))
    implementation(project(":feature:invites"))
    implementation(project(":feature:newchat"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:verification"))
    implementation(project(":feature:call"))

    // Kyocera cover-screen callback declarations: compile-only, never packaged —
    // the phone supplies the real classes (docs/adr/0009).
    compileOnly(project(":stubs:kyocera-sublcd"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    // WorkManager fallback for the Android 15 dataSync FGS runtime cap (ADR 0004).
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Architecture rules (PLAN.md §8.4) run as JVM unit tests in this module.
    testImplementation(libs.konsist)
    testImplementation(libs.junit4)
    // FakeContactsRepository for DirectChatContactSaverTest.
    testImplementation(project(":core:testing"))

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner) // AndroidJUnitRunner
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(libs.hilt.testing)
    kspAndroidTest(libs.hilt.compiler)
}
