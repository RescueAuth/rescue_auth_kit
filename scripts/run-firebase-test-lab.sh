#!/usr/bin/env bash
#
# run-firebase-test-lab.sh
#
# Runs a SINGLE Firebase Test Lab (FTL) instrumentation matrix on one virtual
# Android device for the Rescue Auth Kit v2 project.
#
# Security contract (see docs/FIREBASE_TEST_LAB.md):
#   * Credentials (GCP_SERVICE_ACCOUNT_JSON_BASE64 / FIREBASE_PROJECT_ID) are
#     only ever imported by the `main`+`push` pipeline in .cnb.yml. Never on
#     PR branches or NPC events.
#   * Credentials are never written to gradle.properties, permanent user-home
#     files or the repository checkout.
#   * Sensitive values are never echoed; logs stay redacted.
#   * The service account is de-authorized and every temp file is removed on
#     success, failure and interrupt via a trap.
#   * gcloud is pinned to a fixed version; no `latest`, no unpinned downloads.
#
# Usage:
#   APP_APK=... TEST_APK=... FIREBASE_PROJECT_ID=... \
#   GCP_SERVICE_ACCOUNT_JSON_BASE64=... \
#   FTL_DEVICE_MODEL=... FTL_DEVICE_VERSION=... \
#   FTL_DEVICE_LOCALE=en FTL_DEVICE_ORIENTATION=portrait FTL_TEST_TIMEOUT=5m \
#   CNB_COMMIT=<sha> \
#   scripts/run-firebase-test-lab.sh

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration / constants
# ---------------------------------------------------------------------------

# Pinned, reproducible gcloud toolchain (see docs/FIREBASE_TEST_LAB.md).
readonly GCLOUD_ARCHIVE_URL='https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-sdk-453.0.0-linux-x86_64.tar.gz'
readonly GCLOUD_VERSION='453.0.0'

# FTL exit codes follow the official gcloud `firebase test android run` semantics.
readonly FTL_EXIT_TEST_PASSED=0
readonly FTL_EXIT_GENERAL_CONFIG=1
readonly FTL_EXIT_INVALID_ARGS=2
readonly FTL_EXIT_TEST_FAILED=10
readonly FTL_EXIT_INCONCLUSIVE=15
readonly FTL_EXIT_UNSUPPORTED_ENV=18
readonly FTL_EXIT_TEST_CANCELED=19
readonly FTL_EXIT_INFRASTRUCTURE=20

# Final classification strings (single token, no spaces) that are greppable in
# CNB build logs.
readonly STATUS_PASSED='TEST_PASSED'
readonly STATUS_CONFIG_FAILURE='CONFIGURATION_FAILURE'
readonly STATUS_GENERAL_FAILURE='GENERAL_OR_CONFIGURATION_FAILURE'
readonly STATUS_INVALID_ARGS='INVALID_COMMAND_OR_ARGUMENT'
readonly STATUS_TEST_FAILED='TEST_FAILED'
readonly STATUS_INCONCLUSIVE='INCONCLUSIVE'
readonly STATUS_UNSUPPORTED_ENV='UNSUPPORTED_TEST_ENVIRONMENT'
readonly STATUS_CANCELED='TEST_CANCELED'
readonly STATUS_INFRA_FAILURE='INFRASTRUCTURE_FAILURE'
readonly STATUS_QUOTA='QUOTA_EXHAUSTED'

readonly TMP_ROOT="${TMPDIR:-/tmp}"

# ---------------------------------------------------------------------------
# Redacted logging helpers
# ---------------------------------------------------------------------------

log() { printf '[ftl] %s\n' "$*"; }
err() { printf '[ftl][ERROR] %s\n' "$*" >&2; }

# ---------------------------------------------------------------------------
# Global cleanup state
# ---------------------------------------------------------------------------

declare -a CLEANUP_PATHS=()
GCLOUD_AUTH_ACTIVE=0
PINNED_GCLOUD_BIN=""

# shellcheck disable=SC2317 # cleanup() is reached indirectly via the trap below.
cleanup() {
    # 1) De-authorize the service account (idempotent). Uses the same pinned
    #    gcloud binary the runner installed, so revocation never depends on a
    #    PATH that may differ inside the trap.
    if [[ "$GCLOUD_AUTH_ACTIVE" == "1" ]]; then
        if [[ -n "$PINNED_GCLOUD_BIN" && -x "$PINNED_GCLOUD_BIN" ]]; then
            "$PINNED_GCLOUD_BIN" auth revoke --all >/dev/null 2>&1 || true
        fi
        GCLOUD_AUTH_ACTIVE=0
    fi
    # 2) Remove every sensitive temp file / directory.
    local p
    for p in "${CLEANUP_PATHS[@]:-}"; do
        if [[ -n "$p" ]]; then
            rm -rf -- "$p" 2>/dev/null || true
        fi
    done
    CLEANUP_PATHS=()
}
trap cleanup EXIT INT TERM

register_tmp() {
    local p="$1"
    CLEANUP_PATHS+=("$p")
}

# ---------------------------------------------------------------------------
# Exit-code classification
# ---------------------------------------------------------------------------

# Classifies a raw gcloud exit code and maps it to a final status string.
# Uses an argument because `gcloud ... ; rc=$?` would otherwise be eaten by
# `set -e`. Prints the final status string and returns 0 only for the
# TEST_PASSED (0) case; all other statuses return non-zero so callers can
# propagate a pipeline failure while still preserving the raw exit code.
classify_exit_code() {
    local rc="$1"
    local status
    case "$rc" in
        "$FTL_EXIT_TEST_PASSED")      status="$STATUS_PASSED" ;;
        "$FTL_EXIT_GENERAL_CONFIG")  status="$STATUS_GENERAL_FAILURE" ;;
        "$FTL_EXIT_INVALID_ARGS")    status="$STATUS_INVALID_ARGS" ;;
        "$FTL_EXIT_TEST_FAILED")     status="$STATUS_TEST_FAILED" ;;
        "$FTL_EXIT_INCONCLUSIVE")    status="$STATUS_INCONCLUSIVE" ;;
        "$FTL_EXIT_UNSUPPORTED_ENV") status="$STATUS_UNSUPPORTED_ENV" ;;
        "$FTL_EXIT_TEST_CANCELED")   status="$STATUS_CANCELED" ;;
        "$FTL_EXIT_INFRASTRUCTURE")  status="$STATUS_INFRA_FAILURE" ;;
        *)
            # Unknown exit code: never blindly label it a product test
            # failure. Report it as a general failure.
            status="$STATUS_GENERAL_FAILURE"
            ;;
    esac
    log "FTL_EXIT_CODE=${rc}"
    log "FTL_STATUS=${status}"
    log "FTL_FINAL=${status}"
    if [[ "$rc" == "$FTL_EXIT_TEST_PASSED" ]]; then
        return 0
    fi
    return 1
}

# Extra annotation: only when the captured output text explicitly mentions
# quota / resource exhaustion. Always keeps the raw exit code authoritative.
annotate_quota_if_present() {
    local text="$1"
    if [[ -n "$text" ]] \
        && grep -qiE 'quota|resource.?exhaust|insufficient.*(quota|resource)|429' <<<"$text"; then
        log "FTL_STATUS=${STATUS_QUOTA}"
    fi
}

# ---------------------------------------------------------------------------
# Required environment validation
# ---------------------------------------------------------------------------

require_nonempty() {
    local name="$1"
    local val="${!name:-}"
    if [[ -z "$val" ]]; then
        err "Required environment variable ${name} is empty or unset."
        err "FTL_STATUS=${STATUS_CONFIG_FAILURE}"
        exit "$FTL_EXIT_GENERAL_CONFIG"
    fi
}

# ---------------------------------------------------------------------------
# Sensitive helper
# ---------------------------------------------------------------------------

# Sets GCP_SERVICE_ACCOUNT_JSON to the DECODED service-account JSON, read from
# the base64 env var, without ever printing either value.
decode_service_account_json() {
    local b64="${GCP_SERVICE_ACCOUNT_JSON_BASE64:-}"
    if [[ -z "$b64" ]]; then
        err "GCP_SERVICE_ACCOUNT_JSON_BASE64 is empty or unset."
        err "FTL_STATUS=${STATUS_CONFIG_FAILURE}"
        exit "$FTL_EXIT_GENERAL_CONFIG"
    fi
    # base64 -d accepts missing padding; a value that is not valid base64
    # (including a bare secret, or whitespace) fails here.
    if ! GCP_SERVICE_ACCOUNT_JSON="$(printf '%s' "$b64" | base64 -d 2>/dev/null)"; then
        err "Failed to base64-decode GCP_SERVICE_ACCOUNT_JSON_BASE64."
        err "FTL_STATUS=${STATUS_CONFIG_FAILURE}"
        exit "$FTL_EXIT_GENERAL_CONFIG"
    fi
    if [[ -z "$GCP_SERVICE_ACCOUNT_JSON" ]]; then
        err "Decoded GCP_SERVICE_ACCOUNT_JSON_BASE64 is empty."
        err "FTL_STATUS=${STATUS_CONFIG_FAILURE}"
        exit "$FTL_EXIT_GENERAL_CONFIG"
    fi
    # Quick sanity check: must look like a JSON object with a type field.
    if ! grep -q '"type"' <<<"$GCP_SERVICE_ACCOUNT_JSON"; then
        err "Decoded service account JSON is malformed (no \"type\" field)."
        err "FTL_STATUS=${STATUS_CONFIG_FAILURE}"
        exit "$FTL_EXIT_GENERAL_CONFIG"
    fi
    export GCP_SERVICE_ACCOUNT_JSON
}

# ---------------------------------------------------------------------------
# APK validation (deterministic Gradle output paths)
# ---------------------------------------------------------------------------

validate_apk() {
    local name="$1"
    local path="${!name:-}"
    if [[ -z "$path" ]]; then
        err "Required environment variable ${name} is empty or unset."
        err "FTL_STATUS=${STATUS_CONFIG_FAILURE}"
        exit "$FTL_EXIT_GENERAL_CONFIG"
    fi
    if [[ ! -f "$path" ]]; then
        err "${name} does not exist: ${path}"
        err "FTL_STATUS=${STATUS_CONFIG_FAILURE}"
        exit "$FTL_EXIT_GENERAL_CONFIG"
    fi
    if [[ ! -s "$path" ]]; then
        err "${name} is empty (zero bytes): ${path}"
        err "FTL_STATUS=${STATUS_CONFIG_FAILURE}"
        exit "$FTL_EXIT_GENERAL_CONFIG"
    fi
    # APK files are zip archives; this catches truncated or wrong files.
    if ! unzip -tq "$path" >/dev/null 2>&1; then
        err "${name} is not a valid zip/APK archive: ${path}"
        err "FTL_STATUS=${STATUS_CONFIG_FAILURE}"
        exit "$FTL_EXIT_GENERAL_CONFIG"
    fi
    if ! unzip -Z1 "$path" 2>/dev/null | grep -q 'AndroidManifest.xml'; then
        err "${name} does not look like an Android APK (no AndroidManifest.xml): ${path}"
        err "FTL_STATUS=${STATUS_CONFIG_FAILURE}"
        exit "$FTL_EXIT_GENERAL_CONFIG"
    fi
}

# ---------------------------------------------------------------------------
# Device configuration validation against the FTL catalog
# ---------------------------------------------------------------------------

# Validates that the configured model/version pair exists in the FTL Android
# device catalog and that the device supports VIRTUAL (emulator) form. This
# requires an authenticated gcloud (done before calling it). On any
# uncertainty we fail with CONFIGURATION FAILURE instead of silently falling
# back to a random device.
validate_device_config() {
    local gcloud_bin="$1"
    local model="$2"
    local version="$3"

    if [[ -z "$model" || -z "$version" ]]; then
        err "FTL_DEVICE_MODEL or FTL_DEVICE_VERSION is empty."
        err "FTL_STATUS=${STATUS_CONFIG_FAILURE}"
        exit "$FTL_EXIT_GENERAL_CONFIG"
    fi

    # Ask the authenticated Test Lab service for the catalog entry of the
    # exact model. The response is redacted (we only print booleans).
    local describe_out
    describe_out="$(mktemp "${TMP_ROOT}/ftl-model-desc.XXXXXX.json")"
    chmod 600 "$describe_out"
    register_tmp "$describe_out"

    if ! "$gcloud_bin" firebase test android models describe "$model" \
        >"$describe_out" 2>"${describe_out}.err"; then
        err "FTL device model not found / not queryable: ${model}"
        err "FTL_STATUS=${STATUS_CONFIG_FAILURE}"
        exit "$FTL_EXIT_GENERAL_CONFIG"
    fi

    local version_ok form_ok not_deprecated not_reduced
    version_ok="$(python3 -c '
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    print("no"); raise SystemExit
device_form = d.get("form", "")
versions = d.get("supportedVersionIds", []) or []
print("yes" if sys.argv[2] in [str(v) for v in versions] else "no")
' "$describe_out" "$version")"
    form_ok="$(python3 -c '
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    print("no"); raise SystemExit
print("yes" if str(d.get("form", "")).lower() == "virtual" else "no")
' "$describe_out")"
    not_deprecated="$(python3 -c '
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    print("no"); raise SystemExit
print("no" if d.get("deprecated", False) else "yes")
' "$describe_out")"
    not_reduced="$(python3 -c '
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    print("no"); raise SystemExit
print("no" if d.get("reducedStability", False) else "yes")
' "$describe_out")"

    if [[ "$version_ok" != "yes" ]]; then
        err "FTL device version not supported for model ${model}: API ${version}"
        err "FTL_STATUS=${STATUS_CONFIG_FAILURE}"
        exit "$FTL_EXIT_GENERAL_CONFIG"
    fi
    if [[ "$form_ok" != "yes" ]]; then
        err "FTL device model ${model} is not a virtual device (form=$(python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));print(d.get("form","?"))' "$describe_out" 2>/dev/null || echo unknown))."
        err "FTL_STATUS=${STATUS_CONFIG_FAILURE}"
        exit "$FTL_EXIT_GENERAL_CONFIG"
    fi
    if [[ "$not_deprecated" != "yes" ]]; then
        err "FTL device model ${model} is marked deprecated in the catalog."
        err "FTL_STATUS=${STATUS_CONFIG_FAILURE}"
        exit "$FTL_EXIT_GENERAL_CONFIG"
    fi
    if [[ "$not_reduced" != "yes" ]]; then
        err "FTL device model ${model} is marked reduced_stability in the catalog."
        err "FTL_STATUS=${STATUS_CONFIG_FAILURE}"
        exit "$FTL_EXIT_GENERAL_CONFIG"
    fi
    log "FTL device catalog check passed (virtual, API ${version}, not deprecated, full stability)."
}

# ---------------------------------------------------------------------------
# gcloud bootstrap (pinned version)
# ---------------------------------------------------------------------------

install_pinned_gcloud() {
    local dest="$1"
    if [[ -x "$dest/google-cloud-sdk/bin/gcloud" ]] \
        && "$dest/google-cloud-sdk/bin/gcloud" --version 2>/dev/null \
            | head -1 | grep -q "$GCLOUD_VERSION"; then
        log "gcloud ${GCLOUD_VERSION} already present at ${dest}"
        return 0
    fi
    log "Installing pinned gcloud ${GCLOUD_VERSION} ..."
    local tmp_archive="${TMP_ROOT}/gcloud-${GCLOUD_VERSION}.tar.gz"
    rm -f -- "$tmp_archive"
    if ! curl -fsSL "$GCLOUD_ARCHIVE_URL" -o "$tmp_archive"; then
        err "Failed to download pinned gcloud ${GCLOUD_VERSION}."
        err "FTL_STATUS=${STATUS_CONFIG_FAILURE}"
        exit "$FTL_EXIT_GENERAL_CONFIG"
    fi
    if ! tar -xzf "$tmp_archive" -C "$dest"; then
        err "Failed to extract pinned gcloud ${GCLOUD_VERSION}."
        err "FTL_STATUS=${STATUS_CONFIG_FAILURE}"
        exit "$FTL_EXIT_GENERAL_CONFIG"
    fi
    rm -f -- "$tmp_archive"
    if ! "$dest/google-cloud-sdk/bin/gcloud" --version 2>/dev/null \
        | head -1 | grep -q "$GCLOUD_VERSION"; then
        err "Installed gcloud version does not match pinned ${GCLOUD_VERSION}."
        err "FTL_STATUS=${STATUS_CONFIG_FAILURE}"
        exit "$FTL_EXIT_GENERAL_CONFIG"
    fi
    # Never write usage reports / auto-updates (deterministic behavior).
    "$dest/google-cloud-sdk/bin/gcloud" config set component_manager/disable_update_check true >/dev/null 2>&1 || true
    "$dest/google-cloud-sdk/bin/gcloud" config set disable_usage_reporting true >/dev/null 2>&1 || true
}

# ---------------------------------------------------------------------------
# Authenticate with the service account (redacted)
# ---------------------------------------------------------------------------

authenticate() {
    local gcloud_bin="$1"
    local project="$2"

    local cred_file
    cred_file="$(mktemp "${TMP_ROOT}/ftl-sa.XXXXXX.json")"
    chmod 600 "$cred_file"
    register_tmp "$cred_file"
    printf '%s' "$GCP_SERVICE_ACCOUNT_JSON" > "$cred_file"

    # Set the gcloud config dir under a temp path so we never write anything
    # into permanent user-home files.
    local config_dir
    config_dir="$(mktemp -d "${TMP_ROOT}/ftl-gcloud-config.XXXXXX")"
    chmod 700 "$config_dir"
    register_tmp "$config_dir"

    if ! CLOUDSDK_CONFIG="$config_dir" "$gcloud_bin" auth activate-service-account \
        --key-file="$cred_file" --project="$project" >/dev/null 2>&1; then
        err "gcloud service account authentication failed."
        err "FTL_STATUS=${STATUS_CONFIG_FAILURE}"
        exit "$FTL_EXIT_GENERAL_CONFIG"
    fi
    GCLOUD_AUTH_ACTIVE=1
    export CLOUDSDK_CONFIG="$config_dir"
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

main() {
    # --- Read the interface contract ---
    APP_APK="${APP_APK:-}"
    TEST_APK="${TEST_APK:-}"
    FIREBASE_PROJECT_ID="${FIREBASE_PROJECT_ID:-}"
    FTL_DEVICE_MODEL="${FTL_DEVICE_MODEL:-}"
    FTL_DEVICE_VERSION="${FTL_DEVICE_VERSION:-}"
    FTL_DEVICE_LOCALE="${FTL_DEVICE_LOCALE:-en}"
    FTL_DEVICE_ORIENTATION="${FTL_DEVICE_ORIENTATION:-portrait}"
    FTL_TEST_TIMEOUT="${FTL_TEST_TIMEOUT:-5m}"
    CNB_COMMIT="${CNB_COMMIT:-}"

    require_nonempty APP_APK
    require_nonempty TEST_APK
    require_nonempty FIREBASE_PROJECT_ID
    require_nonempty GCP_SERVICE_ACCOUNT_JSON_BASE64
    require_nonempty FTL_DEVICE_MODEL
    require_nonempty FTL_DEVICE_VERSION
    require_nonempty FTL_DEVICE_LOCALE
    require_nonempty FTL_DEVICE_ORIENTATION
    require_nonempty FTL_TEST_TIMEOUT
    require_nonempty CNB_COMMIT

    # Credential presence check (values are NEVER printed).
    log "FIREBASE_PROJECT_ID set: yes"
    log "GCP_SERVICE_ACCOUNT_JSON_BASE64 set: yes"

    # --- APK validation ---
    validate_apk APP_APK
    validate_apk TEST_APK
    log "APP_APK ok: ${APP_APK}"
    log "TEST_APK ok: ${TEST_APK}"

    # --- Device configuration (fixed, non-sensitive env) ---
    log "FTL_DEVICE_MODEL=${FTL_DEVICE_MODEL}"
    log "FTL_DEVICE_VERSION=${FTL_DEVICE_VERSION}"
    log "FTL_DEVICE_LOCALE=${FTL_DEVICE_LOCALE}"
    log "FTL_DEVICE_ORIENTATION=${FTL_DEVICE_ORIENTATION}"
    log "FTL_TEST_TIMEOUT=${FTL_TEST_TIMEOUT}"
    log "CNB_COMMIT=${CNB_COMMIT}"

    # --- Decode credentials ---
    decode_service_account_json

    # --- Install pinned gcloud ---
    local gcloud_root="${TMP_ROOT}/ftl-gcloud-${GCLOUD_VERSION}"
    mkdir -p "$gcloud_root"
    register_tmp "$gcloud_root"
    install_pinned_gcloud "$gcloud_root"
    local gcloud_bin="$gcloud_root/google-cloud-sdk/bin/gcloud"
    PINNED_GCLOUD_BIN="$gcloud_bin"

    # --- Authenticate (required to query the device catalog) ---
    authenticate "$gcloud_bin" "$FIREBASE_PROJECT_ID"
    log "gcloud auth: ok (project=${FIREBASE_PROJECT_ID})"

    # --- Validate the device configuration against the live FTL catalog ---
    validate_device_config "$gcloud_bin" "$FTL_DEVICE_MODEL" "$FTL_DEVICE_VERSION"
    log "FTL_DEVICE_CONFIG_VALIDATED: model=${FTL_DEVICE_MODEL} version=${FTL_DEVICE_VERSION}"

    # --- Submit the single matrix and WAIT for it to finish ---
    # The raw exit code is captured immediately after the command; the
    # `set -e`-safe pattern guarantees classification + cleanup always run.
    local matrix_id="" rc=0
    set +e
    "$gcloud_bin" firebase test android run \
        --project="$FIREBASE_PROJECT_ID" \
        --type=instrumentation \
        --app="$APP_APK" \
        --test="$TEST_APK" \
        --device=model="$FTL_DEVICE_MODEL",version="$FTL_DEVICE_VERSION",locale="$FTL_DEVICE_LOCALE",orientation="$FTL_DEVICE_ORIENTATION" \
        --timeout="$FTL_TEST_TIMEOUT" \
        --results-dir="ftl-${CNB_COMMIT}" \
        --no-auto-google-login \
        --no-performance-metrics \
        --no-record-video \
        2>&1 | tee "${TMP_ROOT}/ftl-output.log"
    rc=${PIPESTATUS[0]}
    set -e
    register_tmp "${TMP_ROOT}/ftl-output.log"

    log "FTL_RAW_GCLOUD_EXIT_CODE=${rc}"

    # Parse matrix id + console URL from gcloud output (redacted by default).
    matrix_id="$(grep -Eo '[0-9]{16,}' "${TMP_ROOT}/ftl-output.log" | head -1 || true)"
    if [[ -z "$matrix_id" ]]; then
        log "FTL_MATRIX_ID=(not parsed from output)"
    else
        log "FTL_MATRIX_ID=${matrix_id}"
    fi
    log "FTL_CONSOLE=https://console.firebase.google.com/project/${FIREBASE_PROJECT_ID}/testlab/histories"

    # --- Classification ---
    annotate_quota_if_present "$(cat "${TMP_ROOT}/ftl-output.log" 2>/dev/null || true)"
    set +e
    classify_exit_code "$rc"
    local classified_rc=$?
    set -e
    if [[ "$classified_rc" == 0 ]]; then
        exit 0
    fi
    exit "$rc"
}

main "$@"
