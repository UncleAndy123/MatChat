#!/usr/bin/env bash
# Build a patched org.matrix.rustcomponents:sdk-android AAR with MatChat's extra
# FFI methods (docs/VOICE.md §4.1, tools/matrix-shim/README.md).
#
# This is spike / build-infra code. It runs on a Linux/macOS host with a Rust +
# Android NDK toolchain — NEVER needed to build the MatChat app itself. It emits
# a prebuilt .aar the app links like the upstream one.
#
# It fails loudly with guidance rather than guessing: the exact checkout revs and
# the components-kotlin build entrypoint are the things the first run reconciles.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"

SDK_VERSION="${SDK_VERSION:-26.09.3}"
COMPONENTS_TAG="${COMPONENTS_TAG:-v${SDK_VERSION}}"
SHIM_VERSION="${SHIM_VERSION:-${SDK_VERSION}-matchat-shim1}"
WORK_DIR="${WORK_DIR:-$here/work}"
PUBLISH="${PUBLISH:-mavenlocal}" # mavenlocal | file

COMPONENTS_REPO="https://github.com/matrix-org/matrix-rust-components-kotlin.git"

log() { printf '\n\033[1;34m[matrix-shim]\033[0m %s\n' "$*"; }
die() { printf '\n\033[1;31m[matrix-shim] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# --- 0. sanity ------------------------------------------------------------
# Match only the [versions] entry (value is a quoted string); the [libraries]
# entry also starts with `matrix-rustsdk =` but is `{ ... }`, so require a quote
# right after `=` and take the first hit.
catalog_ver="$(grep -E '^matrix-rustsdk[[:space:]]*=[[:space:]]*"' "$repo_root/gradle/libs.versions.toml" | head -n1 | sed -E 's/.*"([^"]+)".*/\1/')"
[ "$catalog_ver" = "$SDK_VERSION" ] || die "SDK_VERSION=$SDK_VERSION but libs.versions.toml pins $catalog_ver — align them first."
: "${ANDROID_NDK_HOME:=${ANDROID_NDK_ROOT:-}}"
[ -n "${ANDROID_NDK_HOME:-}" ] || die "Set ANDROID_NDK_HOME to your Android NDK (r26+)."
command -v cargo >/dev/null || die "Rust toolchain (cargo) not found."
command -v cargo-ndk >/dev/null || die "cargo-ndk not found: cargo install cargo-ndk"
command -v git >/dev/null || die "git not found."

mkdir -p "$WORK_DIR"
components_dir="$WORK_DIR/matrix-rust-components-kotlin"

# --- 1. check out components-kotlin (pulls matrix-rust-sdk it pins) --------
if [ ! -d "$components_dir/.git" ]; then
  log "Cloning matrix-rust-components-kotlin @ $COMPONENTS_TAG"
  git clone --depth 1 --branch "$COMPONENTS_TAG" --recurse-submodules \
    "$COMPONENTS_REPO" "$components_dir" \
    || die "Clone/tag failed. Confirm tag '$COMPONENTS_TAG' exists in $COMPONENTS_REPO (the tag naming for $SDK_VERSION is the first thing to reconcile)."
fi

# The FFI crate lives inside the matrix-rust-sdk checkout referenced by this repo.
# components-kotlin vendors it under one of these; pick whichever exists.
ffi_src=""
for cand in \
  "$components_dir/rust-sdk/bindings/matrix-sdk-ffi/src" \
  "$components_dir/matrix-rust-sdk/bindings/matrix-sdk-ffi/src" \
  "$WORK_DIR/matrix-rust-sdk/bindings/matrix-sdk-ffi/src"; do
  [ -d "$cand" ] && ffi_src="$cand" && break
done
[ -n "$ffi_src" ] || die "Could not locate matrix-sdk-ffi/src under the checkout. Inspect $components_dir and set the path in this script (step 1)."
log "FFI crate src: $ffi_src"

# --- 2. overlay the MatChat FFI methods -----------------------------------
log "Applying overlay (matchat_shim.rs + mod declaration)"
cp "$here/overlay/matchat_shim.rs" "$ffi_src/matchat_shim.rs"
lib_rs="$ffi_src/lib.rs"
[ -f "$lib_rs" ] || die "No lib.rs at $lib_rs"
grep -q '^mod matchat_shim;' "$lib_rs" || printf '\nmod matchat_shim;\n' >> "$lib_rs"

# --- 3. build the AAR -----------------------------------------------------
# components-kotlin ships a build script that cross-compiles the .so per ABI and
# assembles the aar. Its exact flags vary by tag; the common form is below.
# Reconcile against $components_dir once, then this is stable.
log "Building sdk-android AAR (cargo-ndk + uniffi + gradle assemble)"
pushd "$components_dir" >/dev/null
if [ -x "./scripts/build.sh" ]; then
  ./scripts/build.sh -m sdk -p "$(dirname "$(dirname "$ffi_src")")/../.." -t aarch64-linux-android \
    || die "components-kotlin build.sh failed — inspect its --help; flags change across tags."
else
  die "Expected ./scripts/build.sh in components-kotlin (@ $COMPONENTS_TAG). Inspect the repo and wire its build entrypoint here (step 3)."
fi
popd >/dev/null

aar="$(find "$components_dir" -name 'sdk-android*-release.aar' -o -name 'sdk-release.aar' | head -n1)"
[ -n "$aar" ] || die "Build reported success but no AAR found under $components_dir."
log "Built: $aar"

# --- 4. publish -----------------------------------------------------------
case "$PUBLISH" in
  mavenlocal)
    dest="$HOME/.m2/repository/org/matrix/rustcomponents/sdk-android/$SHIM_VERSION"
    mkdir -p "$dest"
    cp "$aar" "$dest/sdk-android-$SHIM_VERSION.aar"
    # Minimal POM so Gradle resolves it (transitive deps of the SDK are declared
    # by the app already; this coordinate only carries the AAR).
    cat > "$dest/sdk-android-$SHIM_VERSION.pom" <<POM
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.matrix.rustcomponents</groupId>
  <artifactId>sdk-android</artifactId>
  <version>$SHIM_VERSION</version>
  <packaging>aar</packaging>
</project>
POM
    log "Published to mavenLocal: org.matrix.rustcomponents:sdk-android:$SHIM_VERSION"
    log "Enable in the app: -Pmatchat.useShimSdk=true -Pmatchat.shimSdkVersion=$SHIM_VERSION"
    ;;
  file)
    out="$WORK_DIR/out"; mkdir -p "$out"
    cp "$aar" "$out/sdk-android-$SHIM_VERSION.aar"
    log "Copied AAR to $out/sdk-android-$SHIM_VERSION.aar"
    ;;
  *) die "Unknown PUBLISH=$PUBLISH (want mavenlocal|file)";;
esac

log "Done."
