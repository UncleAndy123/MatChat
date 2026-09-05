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
        versionCode = 1
        versionName = "0.1.0-M0"
        testInstrumentationRunner = "org.matchat.client.HiltTestRunner"
    }

    // Ship armeabi-v7a + arm64-v8a splits for RELEASE only; the Rust .so is the
    // bulk of the APK and the release size check fails a split over 25 MB
    // (PLAN.md §3, §8.4). Debug stays a single all-ABI APK so it installs on any
    // emulator (incl. x86_64) — there are no native libs until M1, so splitting a
    // debug build only makes it uninstallable on dev machines for no benefit.
    val abiSplitEnabled = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
    splits {
        abi {
            isEnable = abiSplitEnabled
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
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

    implementation(project(":feature:onboarding"))
    implementation(project(":feature:roomlist"))
    implementation(project(":feature:timeline"))
    implementation(project(":feature:invites"))
    implementation(project(":feature:newchat"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:verification"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Architecture rules (PLAN.md §8.4) run as JVM unit tests in this module.
    testImplementation(libs.konsist)
    testImplementation(libs.junit4)

    androidTestImplementation(libs.androidx.test.ext.junit)
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
        }.files
        val tooBig = apks.filter { it.length() > limitBytes }
        require(tooBig.isEmpty()) {
            "Release APK over 25 MB per split: " +
                tooBig.joinToString { "${it.name}=${it.length() / (1024 * 1024)}MB" }
        }
    }
}
