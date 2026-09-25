# local-maven — drop the shim AAR here

A project-local Maven repository so you can consume the CI-built patched
`sdk-android` AAR without touching `~/.m2`. When `-Pmatchat.useShimSdk=true`,
`settings.gradle.kts` adds this folder as a Maven repo (scoped to
`org.matrix.rustcomponents:sdk-android`, default off).

## Drop-in steps

1. Run the **matrix-shim AAR** GitHub Action and download its
   `sdk-android-matchat-shim` artifact; unzip it — you get an `m2/` tree.
2. Copy the **contents of `m2/`** into this folder, so you end up with:

   ```
   tools/matrix-shim/local-maven/
     org/matrix/rustcomponents/sdk-android/26.09.3-matchat-shim1/
       sdk-android-26.09.3-matchat-shim1.aar
       sdk-android-26.09.3-matchat-shim1.pom
   ```

   (The version folder is pre-created; just drop the two files in it. If the
   artifact's `SHIM_VERSION` differs, use that folder name and match
   `matchat.shimSdkVersion`.)
3. In `gradle.properties` (or `-P`):
   ```properties
   matchat.useShimSdk=true
   matchat.shimSdkVersion=26.09.3-matchat-shim1
   ```
4. Gradle sync + build in Android Studio.

Turn the flag back off (remove it) for normal development.

## Committed vs. ignored

The folder structure is committed (via `.gitkeep`); the `.aar` is **not** — a
`.gitignore` rule keeps the ~36 MB native binary out of git. The `.pom` is small
and may be committed or left local; either is fine.
