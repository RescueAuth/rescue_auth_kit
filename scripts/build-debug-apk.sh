#!/usr/bin/env bash
#
# build-debug-apk.sh
#
# Builds a standard Android DEBUG APK for the new RescueAuth app (v2) and
# produces a clearly-named, user-facing debug artifact:
#
#     RescueAuth-<versionName>-debug-<shortCommit>.apk
#
# e.g.  RescueAuth-1.0.0-debug-a1b2c3d.apk
#
# This is the DEVELOPMENT artifact used for real-device smoke testing on any
# feature/fix branch or main. It uses the standard Android debug signing only —
# it NEVER imports the production Secret Repo, NEVER reads production signing
# passwords or keystores, and NEVER runs production signing validation.
#
# The original Gradle output (v2/app/build/outputs/apk/debug/app-debug.apk) is
# left untouched; a byte-identical copy is made under the descriptive name.
#
# Requires an Android SDK + JDK 17 (same as normal v2 dev builds).
#
# Usage: bash scripts/build-debug-apk.sh
#   env overrides:
#     CNB_COMMIT_SHORT / CNB_COMMIT   short commit id for the artifact name

set -euo pipefail

readonly EXPECTED_APPLICATION_ID="com.rescueauth.v2"

die() {
  echo "ERROR: $*" >&2
  exit 1
}

resolve_android_tool() {
  local tool="$1"
  local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  local resolved=""
  if command -v "$tool" >/dev/null 2>&1; then
    command -v "$tool"
    return
  fi
  if [[ -n "$sdk_root" && -d "$sdk_root" ]]; then
    resolved=$(find "$sdk_root" -type f -name "$tool" -perm -u+x 2>/dev/null | sort | tail -n 1)
  fi
  [[ -n "$resolved" ]] || die "Required Android SDK tool is unavailable: ${tool}"
  echo "$resolved"
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
v2_root="$repo_root/v2"

# The short commit id used only for the artifact name (not for versioning).
short_commit="${CNB_COMMIT_SHORT:-}"
if [[ -z "$short_commit" && -n "${CNB_COMMIT:-}" ]]; then
  short_commit="${CNB_COMMIT:0:7}"
fi
if [[ -z "$short_commit" ]]; then
  short_commit="$(git -C "$repo_root" rev-parse --short=7 HEAD 2>/dev/null || echo "dev")"
fi
readonly short_commit

# Core build: standard debug APK. No release signing, no secrets.
cd "$v2_root"
./gradlew --no-daemon :app:assembleDebug

apk_path="$v2_root/app/build/outputs/apk/debug/app-debug.apk"
[[ -s "$apk_path" ]] || die "Debug APK was not produced at $apk_path"

apksigner_bin=$(resolve_android_tool apksigner)
apkanalyzer_bin=$(resolve_android_tool apkanalyzer)

# Debug APK verification (see issue requirements):
#   - applicationId == com.rescueauth.v2
#   - versionName / versionCode come from the actual Gradle build
#   - debuggable == true
#   - valid debug signature
# Debug signer identity is deliberately NOT written into production trust
# metadata, and no production certificate fingerprint is required here.
application_id=$("$apkanalyzer_bin" manifest application-id "$apk_path")
version_name=$("$apkanalyzer_bin" manifest version-name "$apk_path")
version_code=$("$apkanalyzer_bin" manifest version-code "$apk_path")
debuggable=$("$apkanalyzer_bin" manifest debuggable "$apk_path")

[[ "$application_id" == "$EXPECTED_APPLICATION_ID" ]] || die "Debug APK applicationId mismatch: got '$application_id'."
[[ -n "$version_name" ]] || die "Debug APK versionName is empty."
[[ "$version_code" =~ ^[0-9]+$ && "$version_code" -gt 0 ]] || die "Debug APK versionCode is not a valid positive integer: '$version_code'."
[[ "$debuggable" == "true" ]] || die "Debug APK is not debuggable (debuggable='$debuggable')."

"$apksigner_bin" verify "$apk_path" >/dev/null 2>&1 \
  || die "Debug APK failed apksigner verification."

# Byte-identical copy under a descriptive, user-facing artifact name.
readonly debug_dir="$v2_root/app/build/outputs/debug"
readonly debug_apk="$debug_dir/RescueAuth-${version_name}-debug-${short_commit}.apk"
mkdir -p "$debug_dir"
install -m 0644 "$apk_path" "$debug_apk"

if command -v sha256sum >/dev/null 2>&1; then
  src_sha256=$(sha256sum "$apk_path" | awk '{ print toupper($1) }')
  copy_sha256=$(sha256sum "$debug_apk" | awk '{ print toupper($1) }')
else
  src_sha256=$(shasum -a 256 "$apk_path" | awk '{ print toupper($1) }')
  copy_sha256=$(shasum -a 256 "$debug_apk" | awk '{ print toupper($1) }')
fi
[[ "$src_sha256" == "$copy_sha256" ]] \
  || die "Debug renamed APK SHA-256 does not match the source APK."

echo "DEBUG_BUILD=PASS"
echo "APK_PATH=$apk_path"
echo "DEBUG_APK=$debug_apk"
echo "APK_SHA256=$copy_sha256"
echo "APPLICATION_ID=$application_id"
echo "VERSION_NAME=$version_name"
echo "VERSION_CODE=$version_code"
echo "DEBUGGABLE=$debuggable"
echo "SHORT_COMMIT=$short_commit"
