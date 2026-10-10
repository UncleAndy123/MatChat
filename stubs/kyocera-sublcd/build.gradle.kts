// :stubs:kyocera-sublcd — compile-only declarations of Kyocera's private
// cover-screen (sub-LCD) callback interface, so :app can implement it
// (docs/adr/0009). Never packaged: :app depends on it with compileOnly, and on
// the phone the framework's own class is used. Plain Java compiled against the
// SDK's android.jar, so no Android plugin or AAR is involved.
import java.util.Properties

plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Same compileSdk as every Android module. The SDK is found the way AGP finds
// it: local.properties sdk.dir first, then ANDROID_HOME / ANDROID_SDK_ROOT.
val compileSdk = 35
val sdkDir: String =
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use(::load) }.getProperty("sdk.dir") }
        ?: providers.environmentVariable("ANDROID_HOME").orNull
        ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
        ?: error("Android SDK not found: set sdk.dir in local.properties, or ANDROID_HOME")

dependencies {
    compileOnly(files("$sdkDir/platforms/android-$compileSdk/android.jar"))
}
