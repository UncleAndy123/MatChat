#!/usr/bin/env bash
# Build a patched org.matrix.rustcomponents:sdk-android AAR with MatChat's extra
# FFI methods (docs/VOICE.md §4.1, tools/matrix-shim/README.md).
#
# Build-infra / spike code. Runs on a Linux/macOS host with Rust + Android NDK +
# Android SDK — NEVER needed to build the MatChat app itself. Emits a prebuilt
# .aar the app links like the upstream one.
#
# Model (confirmed against matrix-rust-components-kotlin @ sdk-vX):
#   1. clone matrix-rust-components-kotlin at tag sdk-v<version>
#   2. clone matrix-rust-sdk at the exact ref that tag was built from
#   3. overlay our FFI method onto the sdk's bindings/matrix-sdk-ffi crate
#   4. run components-kotlin scripts/build.sh -p <sdk> (cargo xtask cross-compile
#      + uniffi codegen, then gradle assembleRelease) -> the AAR
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"

SDK_VERSION="${SDK_VERSION:-26.09.3}"
COMPONENTS_TAG="${COMPONENTS_TAG:-sdk-v${SDK_VERSION}}"
# matrix-rust-sdk ref that sdk-v26.09.3 was built from (from that release's notes).
# Override if you bump SDK_VERSION.
SDK_REF="${SDK_REF:-f4b9512df23332fce1bd26037ef7a2387af2ced2}"
SHIM_VERSION="${SHIM_VERSION:-${SDK_VERSION}-matchat-shim1}"
# Single ABI keeps the spike's first green fast. Set empty to build ALL targets
# (needed for a device-agnostic AAR), or another target for the real phone, e.g.
# armv7-linux-androideabi.
ONLY_TARGET="${ONLY_TARGET:-aarch64-linux-android}"
WORK_DIR="${WORK_DIR:-$here/work}"
PUBLISH="${PUBLISH:-mavenlocal}" # mavenlocal | file

COMPONENTS_REPO="https://github.com/matrix-org/matrix-rust-components-kotlin.git"
SDK_REPO="https://github.com/matrix-org/matrix-rust-sdk.git"

log() { printf '\n\033[1;34m[matrix-shim]\033[0m %s\n' "$*"; }
die() { printf '\n\033[1;31m[matrix-shim] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# --- 0. sanity ------------------------------------------------------------
catalog_ver="$(grep -E '^matrix-rustsdk[[:space:]]*=[[:space:]]*"' "$repo_root/gradle/libs.versions.toml" | head -n1 | sed -E 's/.*"([^"]+)".*/\1/')"
[ "$catalog_ver" = "$SDK_VERSION" ] || die "SDK_VERSION=$SDK_VERSION but libs.versions.toml pins $catalog_ver — align them first."
: "${ANDROID_NDK_HOME:=${ANDROID_NDK_ROOT:-}}"
[ -n "${ANDROID_NDK_HOME:-}" ] || die "Set ANDROID_NDK_HOME to your Android NDK (r26+)."
export ANDROID_NDK_HOME ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
command -v cargo >/dev/null || die "Rust toolchain (cargo) not found."
command -v cargo-ndk >/dev/null || die "cargo-ndk not found: cargo install cargo-ndk"
command -v git >/dev/null || die "git not found."

mkdir -p "$WORK_DIR"
components_dir="$WORK_DIR/matrix-rust-components-kotlin"
sdk_dir="$WORK_DIR/matrix-rust-sdk"

# --- 1. check out components-kotlin @ tag ---------------------------------
if [ ! -d "$components_dir/.git" ]; then
  log "Cloning matrix-rust-components-kotlin @ $COMPONENTS_TAG"
  git clone --depth 1 --branch "$COMPONENTS_TAG" "$COMPONENTS_REPO" "$components_dir" \
    || die "Clone/tag failed. Confirm tag '$COMPONENTS_TAG' exists in $COMPONENTS_REPO."
fi
[ -x "$components_dir/scripts/build.sh" ] || die "Expected scripts/build.sh in components-kotlin @ $COMPONENTS_TAG."

# --- 2. check out matrix-rust-sdk @ the ref that tag was built from --------
if [ ! -d "$sdk_dir/.git" ]; then
  log "Cloning matrix-rust-sdk (full; a specific ref is needed)"
  git clone "$SDK_REPO" "$sdk_dir" || die "Clone of matrix-rust-sdk failed."
fi
log "Checking out matrix-rust-sdk @ $SDK_REF"
git -C "$sdk_dir" fetch --all --tags --quiet || true
git -C "$sdk_dir" checkout --quiet "$SDK_REF" || die "Ref '$SDK_REF' not found in matrix-rust-sdk — reconcile SDK_REF with the $COMPONENTS_TAG release notes."

ffi_src="$sdk_dir/bindings/matrix-sdk-ffi/src"
[ -d "$ffi_src" ] || die "No matrix-sdk-ffi/src at $ffi_src (SDK layout changed?)."

# --- 3. overlay the MatChat FFI method ------------------------------------
log "Applying overlay (matchat_shim.rs + mod declaration)"
cp "$here/overlay/matchat_shim.rs" "$ffi_src/matchat_shim.rs"
grep -q '^mod matchat_shim;' "$ffi_src/lib.rs" || printf '\nmod matchat_shim;\n' >> "$ffi_src/lib.rs"

# --- 4. build the AAR (release) -------------------------------------------
log "Building sdk-android AAR (cargo xtask cross-compile + uniffi + gradle)"
build_args=(-r -p "$sdk_dir" -m sdk)
[ -n "$ONLY_TARGET" ] && build_args+=(-t "$ONLY_TARGET") && log "target: $ONLY_TARGET (set ONLY_TARGET= empty for all ABIs)"
( cd "$components_dir" && bash scripts/build.sh "${build_args[@]}" ) \
  || die "components-kotlin build.sh failed — see the log above (rust compile / uniffi / gradle)."

aar="$components_dir/sdk/sdk-android/build/outputs/aar/sdk-android-release.aar"
[ -f "$aar" ] || die "Build reported success but AAR not at $aar."
log "Built: $aar"

# --- 5. publish -----------------------------------------------------------
# The POM must carry the SAME transitive deps as the upstream sdk-android POM —
# especially JNA, the uniffi Kotlin runtime binding — or the app resolves/links
# but crashes at runtime. Kept in sync with sdk-android-$SDK_VERSION.pom.
write_pom() {
  cat > "$1" <<POM
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.matrix.rustcomponents</groupId>
  <artifactId>sdk-android</artifactId>
  <version>$SHIM_VERSION</version>
  <packaging>aar</packaging>
  <dependencies>
    <dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib</artifactId><version>1.9.25</version><scope>compile</scope></dependency>
    <dependency><groupId>net.java.dev.jna</groupId><artifactId>jna</artifactId><version>5.18.1</version><type>aar</type><scope>runtime</scope>
      <exclusions><exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion></exclusions></dependency>
    <dependency><groupId>org.jetbrains.kotlinx</groupId><artifactId>kotlinx-coroutines-core</artifactId><version>1.7.3</version><scope>runtime</scope></dependency>
    <dependency><groupId>androidx.annotation</groupId><artifactId>annotation</artifactId><version>1.9.1</version><scope>runtime</scope></dependency>
  </dependencies>
</project>
POM
}

case "$PUBLISH" in
  mavenlocal)
    dest="$HOME/.m2/repository/org/matrix/rustcomponents/sdk-android/$SHIM_VERSION"
    mkdir -p "$dest"
    cp "$aar" "$dest/sdk-android-$SHIM_VERSION.aar"
    write_pom "$dest/sdk-android-$SHIM_VERSION.pom"
    log "Published to mavenLocal: org.matrix.rustcomponents:sdk-android:$SHIM_VERSION"
    log "Enable in the app: -Pmatchat.useShimSdk=true -Pmatchat.shimSdkVersion=$SHIM_VERSION"
    ;;
  file)
    # Drop-in Maven layout: merge out/m2/ into your ~/.m2/repository/ then build
    # the app with -Pmatchat.useShimSdk=true -Pmatchat.shimSdkVersion=$SHIM_VERSION.
    dest="$WORK_DIR/out/m2/org/matrix/rustcomponents/sdk-android/$SHIM_VERSION"
    mkdir -p "$dest"
    cp "$aar" "$dest/sdk-android-$SHIM_VERSION.aar"
    write_pom "$dest/sdk-android-$SHIM_VERSION.pom"
    log "Wrote drop-in Maven layout under $WORK_DIR/out/m2 (merge into ~/.m2/repository)"
    ;;
  *) die "Unknown PUBLISH=$PUBLISH (want mavenlocal|file)";;
esac

log "Done."
