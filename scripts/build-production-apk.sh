#!/usr/bin/env bash

set -euo pipefail
# Secret-bearing callers must never enable tracing around this script.
set +x

readonly EXPECTED_APPLICATION_ID="com.rescueauth.v2"
readonly EXPECTED_ALIAS="rescueauth-v2"

# The production release tag (e.g. `rescueauth-v1.0.0`) that this pipeline
# builds from. On a tag_push event CNB_BRANCH is the tag name. A caller may
# override via RESCUEAUTH_RELEASE_TAG for testing. There is deliberately NO
# hardcoded version here: the release version is derived from the tag so the
# same script works for every future rescueauth-vX.Y.Z release.
readonly release_tag="${RESCUEAUTH_RELEASE_TAG:-${CNB_BRANCH:-}}"

die() {
  echo "ERROR: $*" >&2
  exit 1
}

require_secret() {
  local name="$1"
  [[ -n "${!name:-}" ]] || die "Required production signing variable is missing: ${name}"
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

normalize_fingerprint() {
  tr -d '[:space:]:' | tr '[:lower:]' '[:upper:]'
}

for secret_name in \
  RESCUEAUTH_KEYSTORE_BASE64 \
  RESCUEAUTH_STORE_PASSWORD \
  RESCUEAUTH_KEY_PASSWORD \
  RESCUEAUTH_KEY_ALIAS; do
  require_secret "$secret_name"
done

[[ "$RESCUEAUTH_KEY_ALIAS" == "$EXPECTED_ALIAS" ]] || die "Production key alias does not match the pinned release identity."

readonly repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly v2_root="$repo_root/v2"

# Load the release-tag parsing helpers (tag format validation + version
# extraction). This enforces the long-term release contract: production is
# built ONLY from a valid namespaced `rescueauth-vX.Y.Z` tag.
source "$repo_root/scripts/release-version.sh"

# ---------------------------------------------------------------------------
# Release-tag gate (FAIL CLOSED)
# ---------------------------------------------------------------------------
# The production release version is derived from the tag, never from a
# hardcoded string and never from `main`. If the tag is missing or does not
# match `^rescueauth-v[0-9]+\.[0-9]+\.[0-9]+$`, the build stops immediately.
# This rejects legacy tags (legacy-vX.Y.Z), `main`, feature branches,
# and arbitrary strings.
if [[ -z "$release_tag" ]]; then
  die "No release tag provided: production release must be built from a rescueauth-vX.Y.Z tag (got empty CNB_BRANCH / RESCUEAUTH_RELEASE_TAG)."
fi
if ! release_tag_is_valid "$release_tag"; then
  die "Invalid production release tag '$release_tag': must match ^rescueauth-v[0-9]+\.[0-9]+\.[0-9]+$ (legacy tags and non-release branches are not accepted)."
fi
readonly expected_version_name="$(release_version_from_tag "$release_tag")"
[[ -n "$expected_version_name" ]] || die "Could not derive release version from tag '$release_tag'."

readonly certificate_metadata="$v2_root/release/android-signing-certificate.txt"
[[ -f "$certificate_metadata" ]] || die "Pinned production certificate metadata is missing."

expected_cert_sha256=$(awk -F= '$1 == "certificateSha256" { print $2 }' "$certificate_metadata" | normalize_fingerprint)
[[ "$expected_cert_sha256" =~ ^[0-9A-F]{64}$ ]] || die "Pinned production certificate fingerprint is invalid."

readonly temp_parent="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
[[ -d "$temp_parent" ]] || die "Runner temporary directory does not exist."
umask 077
signing_temp_dir=$(mktemp -d "${temp_parent%/}/rescueauth-signing.XXXXXX")
chmod 700 "$signing_temp_dir"
readonly signing_temp_dir
readonly keystore_path="$signing_temp_dir/rescueauth-v2-release.p12"

cleanup() {
  set +e
  if [[ -n "${signing_temp_dir:-}" && -d "$signing_temp_dir" ]]; then
    rm -f -- "$keystore_path"
    rm -f -- "$signing_temp_dir/apksigner-verification.txt"
    rmdir -- "$signing_temp_dir"
  fi
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

# Decode directly from the imported environment variable into the ephemeral
# 0600 file. The value is never echoed, piped through a traced command, cached,
# or written beneath the repository workspace.
if base64 --decode </dev/null >/dev/null 2>&1; then
  base64 --decode >"$keystore_path" <<<"$RESCUEAUTH_KEYSTORE_BASE64"
else
  base64 -D >"$keystore_path" <<<"$RESCUEAUTH_KEYSTORE_BASE64"
fi
unset RESCUEAUTH_KEYSTORE_BASE64
chmod 600 "$keystore_path"
[[ -s "$keystore_path" ]] || die "Decoded production keystore is empty."

export RESCUEAUTH_STORE_FILE="$keystore_path"

command -v keytool >/dev/null 2>&1 || die "keytool is unavailable."
command -v openssl >/dev/null 2>&1 || die "openssl is unavailable."

keystore_cert_sha256=$(
  keytool -exportcert \
    -storetype PKCS12 \
    -keystore "$keystore_path" \
    -alias "$RESCUEAUTH_KEY_ALIAS" \
    -storepass:env RESCUEAUTH_STORE_PASSWORD \
    | openssl x509 -inform DER -noout -fingerprint -sha256 \
    | cut -d= -f2 \
    | normalize_fingerprint
)
[[ "$keystore_cert_sha256" == "$expected_cert_sha256" ]] || die "Decoded keystore certificate does not match the pinned production identity."

cd "$v2_root"
./gradlew --no-daemon :app:validateReleaseSigning :app:assembleRelease

apk_path="$v2_root/app/build/outputs/apk/release/app-release.apk"
[[ -s "$apk_path" ]] || die "Expected signed release APK was not produced."

apksigner_bin=$(resolve_android_tool apksigner)
apkanalyzer_bin=$(resolve_android_tool apkanalyzer)
verification_file="$signing_temp_dir/apksigner-verification.txt"

# Parse the authoritative signer COUNT from apksigner's verbose `--print-certs`
# output. The real output emits a single summary line `Number of signers: N`.
# Per-signer metadata lines such as `V2 Signer: certificate SHA-256 digest:`
# describe ONE signer and must NOT be counted. We rely solely on the summary
# line and FAIL CLOSED when it is missing or not a clean integer.
#
# Returns the count on stdout, or nothing (empty) when undeterminable.
parse_signer_count() { # verification-file
  local file="$1"
  local line
  line=$(grep -E '^Number of signers: [0-9]+$' "$file" 2>/dev/null | head -n 1 || true)
  [[ -n "$line" ]] || return 0
  grep -oE '[0-9]+$' <<<"$line"
}

# Extract the first signer's certificate SHA-256 digest from apksigner's
# verbose `--print-certs` output. The digest line is `V2 Signer: certificate
# SHA-256 digest: <hex>`; we normalize it to the canonical uppercase form the
# pinned fingerprint comparison expects. Returns the normalized digest, or
# nothing when the line is absent.
parse_signer_fingerprint() { # verification-file
  local file="$1"
  local line
  line=$(grep -E '^V2 Signer: certificate SHA-256 digest:' "$file" 2>/dev/null | head -n 1 || true)
  [[ -n "$line" ]] || return 0
  sed -n 's/^V2 Signer: certificate SHA-256 digest: //p' <<<"$line" | normalize_fingerprint
}

"$apksigner_bin" verify --verbose --print-certs "$apk_path" | tee "$verification_file"

signer_count=$(parse_signer_count "$verification_file")
[[ "$signer_count" == "1" ]] || die "Release APK must contain exactly one signer (found: ${signer_count:-0})."

apk_cert_sha256=$(parse_signer_fingerprint "$verification_file")
[[ -n "$apk_cert_sha256" ]] || die "Release APK signer certificate digest could not be determined."
[[ "$apk_cert_sha256" == "$expected_cert_sha256" ]] || die "Release APK signer does not match the pinned production identity."

application_id=$("$apkanalyzer_bin" manifest application-id "$apk_path")
version_name=$("$apkanalyzer_bin" manifest version-name "$apk_path")
version_code=$("$apkanalyzer_bin" manifest version-code "$apk_path")
debuggable=$("$apkanalyzer_bin" manifest debuggable "$apk_path")

[[ "$application_id" == "$EXPECTED_APPLICATION_ID" ]] || die "Release APK applicationId mismatch."
[[ "$version_name" == "$expected_version_name" ]] || die "Release APK versionName mismatch: tag '$release_tag' implies versionName '$expected_version_name' but the APK reports '$version_name'."
# versionCode is never derived from the tag; it comes from the actual Gradle
# build. We require it to be a valid positive integer and report the real
# value. Each future release must keep versionCode monotonically increasing
# within the new App's own release line (see RELEASE_PROVISIONING.md §3).
[[ "$version_code" =~ ^[0-9]+$ && "$version_code" -gt 0 ]] || die "Release APK versionCode is not a valid positive integer: '$version_code'."
[[ "$debuggable" == "false" ]] || die "Release APK is debuggable."

if command -v sha256sum >/dev/null 2>&1; then
  apk_sha256=$(sha256sum "$apk_path" | awk '{ print toupper($1) }')
else
  apk_sha256=$(shasum -a 256 "$apk_path" | awk '{ print toupper($1) }')
fi

if [[ -n "${RESCUEAUTH_SIGNED_APK_OUTPUT:-}" ]]; then
  install -m 0644 "$apk_path" "$RESCUEAUTH_SIGNED_APK_OUTPUT"
  apk_path="$RESCUEAUTH_SIGNED_APK_OUTPUT"
fi

# Deterministic, user-facing production artifact name. The original Gradle
# output (app-release.apk) is left untouched; we make a byte-identical copy
# under a versioned filename that the attachment upload stage consumes.
# The version in the filename is derived from the release tag (e.g. tag
# `rescueauth-v1.0.0` -> `RescueAuth-1.0.0.apk`).
readonly production_dir="$v2_root/app/build/outputs/production"
readonly production_apk="$production_dir/RescueAuth-${expected_version_name}.apk"
mkdir -p "$production_dir"
install -m 0644 "$apk_path" "$production_apk"

# Recompute the SHA-256 of the renamed copy and require it to match the
# already-verified source APK byte-for-byte before reporting success. This is
# the same bytes verification the attachment stage relies on.
if command -v sha256sum >/dev/null 2>&1; then
  production_sha256=$(sha256sum "$production_apk" | awk '{ print toupper($1) }')
else
  production_sha256=$(shasum -a 256 "$production_apk" | awk '{ print toupper($1) }')
fi
[[ "$production_sha256" == "$apk_sha256" ]] \
  || die "Production renamed APK SHA-256 does not match the verified source APK."

echo "PRODUCTION_SIGNING=PASS"
echo "RELEASE_TAG=$release_tag"
echo "APK_PATH=$apk_path"
echo "APK_SHA256=$apk_sha256"
echo "APK_SIGNER_CERT_SHA256=$apk_cert_sha256"
echo "APPLICATION_ID=$application_id"
echo "VERSION_NAME=$version_name"
echo "VERSION_CODE=$version_code"
echo "DEBUGGABLE=$debuggable"
echo "PRODUCTION_APK=$production_apk"
echo "PRODUCTION_APK_SHA256=$production_sha256"
