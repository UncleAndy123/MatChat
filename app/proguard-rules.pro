# R8 full mode (PLAN.md §4). The Matrix Rust SDK reaches its native .so through
# uniffi + JNA, resolving classes and members by name/reflection, so a minified
# release (isMinifyEnabled) must not rename or strip them — otherwise sign-in
# fails at runtime with `com.sun.jna.Native`. Keep the SDK's public surface and
# its uniffi bindings whole.
-keep class org.matrix.rustcomponents.sdk.** { *; }
-keep class uniffi.** { *; }

# JNA itself: com.sun.jna.Native has JNI-registered native methods, and
# Library/Structure/Callback subtypes are instantiated reflectively — renaming
# any of them breaks the binding. JNA also references desktop java.awt types
# that don't exist on Android, so silence those warnings.
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keep class * implements com.sun.jna.Callback { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**

# Hilt/Dagger generate their own keep rules. Navigation needs Fragment names,
# which are referenced from nav_graph.xml and kept by AGP's resource shrinker.

# Keep enum values used via valueOf (policy choices).
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
