# R8 full mode (PLAN.md §4). Keep the Matrix Rust SDK's JNI surface intact when
# it is wired in M1 — the native layer looks up these by name.
# -keep class org.matrix.rustcomponents.sdk.** { *; }   # uncomment at M1

# Hilt/Dagger generate their own keep rules. Navigation needs Fragment names,
# which are referenced from nav_graph.xml and kept by AGP's resource shrinker.

# Keep enum values used via valueOf (policy choices).
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
