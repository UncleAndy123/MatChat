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
        versionCode = 6
        versionName = "0.1.6-M0"
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

    buildFeatures { viewBinding = true }
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

    // Vendored Android TLS verifier classes (org.rustls.platformverifier) that the
    // Matrix SDK's native lib calls by name over JNI to validate the homeserver
    // certificate. The sdk-android AAR bundles NONE of these, and the artifact is
    // not on Maven Central (it ships only inside the rustls-platform-verifier-android
    // crate's local maven repo), so we vendor the compiled classes here. Paired
    // with the initPlatform() call in :core:matrix (RustMatrixClientHolder) and the
    // keep rule in proguard-rules.pro. Without it, release sign-in fails with
    // "Expect rustls-platform-verifier to be initialized".
    //
    // VERSION LOCK — must match the rustls-platform-verifier version compiled into
    // sdk-android (libs.versions.toml `matrix-rustsdk`). Today: matrix-rustsdk
    // 26.09.3 embeds rustls-platform-verifier 0.6.2, which pulls
    // rustls-platform-verifier-android 0.1.0 (this jar). When bumping the SDK,
    // re-check with:
    //   unzip -p <sdk-android.aar> jni/arm64-v8a/libmatrix_sdk_ffi.so | strings | grep rustls-platform-verifier-
    // and if the version changed, re-vendor the matching crate's AAR classes.jar.
    implementation(files("libs/rustls-platform-verifier-android-0.1.0.jar"))

    implementation(project(":feature:onboarding"))
    implementation(project(":feature:roomlist"))
    implementation(project(":feature:timeline"))
    implementation(project(":feature:invites"))
    implementation(project(":feature:newchat"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:verification"))
    implementation(project(":feature:call"))

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

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner) // AndroidJUnitRunner
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(libs.hilt.testing)
    kspAndroidTest(libs.hilt.compiler)
}

// APK size gate (PLAN.md §8.4): fail if any release ABI split exceeds 25 MB.
tasks.register("checkApkSize") {
    group = "verification"
    description = "Fails if a release APK split is larger than 25 MB."
    dependsOn("assembleRelease")
    doLast {
        val limitBytes = 25L * 1024 * 1024
        val apks = fileTree("${layout.buildDirectory.get()}/outputs/apk/release") {
            include("**/*.apk")
            // The universal APK bundles every ABI for the in-app updater; the
            // 25 MB gate is a per-split download budget, so it doesn't apply.
            exclude("**/*universal*.apk")
        }.files
        val tooBig = apks.filter { it.length() > limitBytes }
        require(tooBig.isEmpty()) {
            "Release APK over 25 MB per split: " +
                tooBig.joinToString { "${it.name}=${it.length() / (1024 * 1024)}MB" }
        }
    }
}
