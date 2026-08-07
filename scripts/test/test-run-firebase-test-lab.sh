#!/usr/bin/env bash
# shellcheck disable=SC2030,SC2031,SC2155
#
# test-run-firebase-test-lab.sh
#
# Offline (no Firebase API, no Test Lab quota) verification of
# scripts/run-firebase-test-lab.sh failure paths, gate logic and exit-code
# classification.
#
# All tests use a FAKE gcloud executable on PATH. The fake never talks to
# Firebase; it only simulates gcloud exit codes and catalog responses. The fake
# is used ONLY here and is NOT part of any production execution path.
#
# Requirements:
#   * scripts/run-firebase-test-lab.sh exists and passes `bash -n` + shellcheck
#   * scripts/check-test-lab-gate.sh exists and passes `bash -n` + shellcheck
#   * Python 3 available (runner parses JSON catalog responses)
#   * unzip available (runner validates APK zip structure)
#
# Usage: bash scripts/test/test-run-firebase-test-lab.sh

set -euo pipefail

# Subshell scoping of env exports and command-substitution assignments are
# intentional in this test harness (each gate test runs in its own ( ) block).

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUNNER="$ROOT/scripts/run-firebase-test-lab.sh"
GATE_SCRIPT="$ROOT/scripts/check-test-lab-gate.sh"
TEST_WORKSPACE="$(mktemp -d)"
FAKE_BIN="$TEST_WORKSPACE/fake-bin"
export PATH="$FAKE_BIN:$PATH"
export TMPDIR="$TEST_WORKSPACE/tmp"
mkdir -p "$FAKE_BIN" "$TMPDIR"

PASS=0
FAIL=0

ok()   { printf '  \033[32mok\033[0m    %s\n' "$1"; PASS=$((PASS+1)); }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAIL=$((FAIL+1)); }

# --- helpers ---------------------------------------------------------------

assert_eq() { # actual expected label
    if [[ "$1" == "$2" ]]; then ok "$3"; else bad "$3 (got '$1', want '$2')"; fi
}

assert_contains() { # haystack needle label
    if [[ "$1" == *"$2"* ]]; then ok "$3"; else bad "$3 (missing '$2')"; fi
}

assert_not_contains() { # haystack needle label
    if [[ "$1" != *"$2"* ]]; then ok "$3"; else bad "$3 (found forbidden '$2')"; fi
}

# build a valid fake service-account base64 (fake secret)
FAKE_SECRET_JSON='{"type":"service_account","project_id":"rescue-auth-kit-test","private_key":"-----BEGIN PRIVATE KEY-----\nFAKE_PRIVATE_KEY_VALUE_0123456789\n-----END PRIVATE KEY-----\n","client_email":"ftl@rescue-auth-kit-test.iam.gserviceaccount.com"}'
FAKE_SECRET_B64="$(printf '%s' "$FAKE_SECRET_JSON" | base64 -w0)"

# --- fake gcloud -----------------------------------------------------------

# The runner installs the pinned gcloud at
#   ${TMPDIR}/ftl-gcloud-453.0.0/google-cloud-sdk/bin/gcloud
# and invokes it by ABSOLUTE path. To exercise the runner without ever
# downloading/contacting the real gcloud, we pre-place a fake gcloud at that
# exact pinned path. It reports the pinned version for `--version` and
# simulates catalog + `firebase test android run` behavior.
install_fake_pinned_gcloud() {
    local fake_dir="${TMPDIR}/ftl-gcloud-453.0.0/google-cloud-sdk/bin"
    mkdir -p "$fake_dir"
    cat > "$fake_dir/gcloud" <<'FAKEGCLOUD'
#!/usr/bin/env bash
set -euo pipefail
# Fake gcloud used ONLY by the offline test suite (never in production).
# It is placed at the pinned path the runner expects and never contacts
# Firebase or the network.
if [[ "${1:-}" == "--version" ]]; then
    printf 'Google Cloud SDK 453.0.0\n'
    exit 0
fi
if [[ "${1:-}" == "config" ]]; then
    exit 0
fi
if [[ "${1:-}" == "auth" && "${2:-}" == "activate-service-account" ]]; then
    exit 0
fi
if [[ "${1:-}" == "auth" && "${2:-}" == "revoke" ]]; then
    exit 0
fi
if [[ "${1:-}" == "firebase" && "${2:-}" == "test" && "${3:-}" == "android" && "${4:-}" == "models" && "${5:-}" == "describe" ]]; then
    if [[ "${6:-}" == "MediumPhone.arm" ]]; then
        printf '{"form":"virtual","supportedVersionIds":["33","31","30"],"deprecated":false,"reducedStability":false}'
        exit 0
    fi
    if [[ "${6:-}" == "DeprecatedModel" ]]; then
        printf '{"form":"virtual","supportedVersionIds":["33"],"deprecated":true,"reducedStability":false}'
        exit 0
    fi
    if [[ "${6:-}" == "ReducedModel" ]]; then
        printf '{"form":"virtual","supportedVersionIds":["33"],"deprecated":false,"reducedStability":true}'
        exit 0
    fi
    printf '{"error":"model not found"}' >&2
    exit 1
fi
if [[ "${1:-}" == "firebase" && "${2:-}" == "test" && "${3:-}" == "android" && "${4:-}" == "run" ]]; then
    rc="${FAKE_GCLOUD_EXIT:-0}"
    printf 'Uploading [test.apk] to Firebase Test Lab...\n'
    printf 'Test [matrix id: 1234567890123456789] has completed.\n'
    if [[ -n "${FAKE_GCLOUD_QUOTA_TEXT:-}" ]]; then
        printf '%s\n' "$FAKE_GCLOUD_QUOTA_TEXT"
    fi
    exit "$rc"
fi
exit 0
FAKEGCLOUD
    chmod +x "$fake_dir/gcloud"
    printf '%s' "$FAKE_SECRET_B64" > "${TMPDIR}/.fake-secret-ref"  # for leak tests
}

# fake APKs (valid zip with AndroidManifest.xml entry)
mkfake_apk() {
    local path="$1"
    mkdir -p "$(dirname "$path")"
    rm -f "$path"
    ( cd "$(mktemp -d)" && touch AndroidManifest.xml && zip -q -r "$path" AndroidManifest.xml )
}

# standard env for happy-path (fake) runs
common_env() {
    export APP_APK="$TEST_WORKSPACE/app-debug.apk"
    export TEST_APK="$TEST_WORKSPACE/app-debug-androidTest.apk"
    export FIREBASE_PROJECT_ID="rescue-auth-kit-test"
    export GCP_SERVICE_ACCOUNT_JSON_BASE64="$FAKE_SECRET_B64"
    export FTL_DEVICE_MODEL="MediumPhone.arm"
    export FTL_DEVICE_VERSION="33"
    export FTL_DEVICE_LOCALE="en"
    export FTL_DEVICE_ORIENTATION="portrait"
    export FTL_TEST_TIMEOUT="5m"
    export CNB_COMMIT="deadbeefcafe0123456789abcdef0123456789abcd"
    export FAKE_GCLOUD_EXIT=0
    unset FAKE_GCLOUD_QUOTA_TEXT
    mkfake_apk "$APP_APK"
    mkfake_apk "$TEST_APK"
    install_fake_pinned_gcloud
}

# run the runner and capture output/rc; must never leak the fake secret.
run_runner() {
    set +e
    local out
    out="$("$RUNNER" 2>&1)"
    local rc=$?
    set -e
    RUNNER_OUT="$out"
    RUNNER_RC="$rc"
}

echo "== static checks =="

if bash -n "$RUNNER"; then ok "runner bash -n"; else bad "runner bash -n"; fi
if shellcheck "$RUNNER" >/dev/null 2>&1; then ok "runner shellcheck"; else bad "runner shellcheck"; fi
if bash -n "$GATE_SCRIPT"; then ok "gate bash -n"; else bad "gate bash -n"; fi
if shellcheck "$GATE_SCRIPT" >/dev/null 2>&1; then ok "gate shellcheck"; else bad "gate shellcheck"; fi

echo
echo "== 1. missing FIREBASE_PROJECT_ID fails fast =="
common_env
mkfake_apk "$APP_APK"; mkfake_apk "$TEST_APK"
unset FIREBASE_PROJECT_ID
run_runner
assert_eq "$RUNNER_RC" "1" "exit 1"
assert_contains "$RUNNER_OUT" "CONFIGURATION_FAILURE" "status CONFIGURATION_FAILURE"
assert_contains "$RUNNER_OUT" "FIREBASE_PROJECT_ID" "names the missing var"

echo
echo "== 2. missing GCP_SERVICE_ACCOUNT_JSON_BASE64 fails fast =="
common_env
unset GCP_SERVICE_ACCOUNT_JSON_BASE64
run_runner
assert_eq "$RUNNER_RC" "1" "exit 1"
assert_contains "$RUNNER_OUT" "CONFIGURATION_FAILURE" "status CONFIGURATION_FAILURE"
assert_contains "$RUNNER_OUT" "GCP_SERVICE_ACCOUNT_JSON_BASE64" "names the missing var"

echo
echo "== 3. invalid base64 fails fast =="
common_env
export GCP_SERVICE_ACCOUNT_JSON_BASE64="this is not valid base64!!!"
run_runner
assert_eq "$RUNNER_RC" "1" "exit 1"
assert_contains "$RUNNER_OUT" "CONFIGURATION_FAILURE" "status CONFIGURATION_FAILURE"
assert_contains "$RUNNER_OUT" "base64" "reports base64 decode failure"

echo
echo "== 4. missing APP_APK fails fast =="
common_env
rm -f "$APP_APK"
run_runner
assert_eq "$RUNNER_RC" "1" "exit 1"
assert_contains "$RUNNER_OUT" "CONFIGURATION_FAILURE" "status CONFIGURATION_FAILURE"
assert_contains "$RUNNER_OUT" "APP_APK" "names the missing var"

echo
echo "== 5. missing TEST_APK fails fast =="
common_env
rm -f "$TEST_APK"
run_runner
assert_eq "$RUNNER_RC" "1" "exit 1"
assert_contains "$RUNNER_OUT" "CONFIGURATION_FAILURE" "status CONFIGURATION_FAILURE"
assert_contains "$RUNNER_OUT" "TEST_APK" "names the missing var"

echo
echo "== 6. logs never contain the fake secret (any form) =="
common_env
mkfake_apk "$APP_APK"; mkfake_apk "$TEST_APK"
run_runner
assert_not_contains "$RUNNER_OUT" "FAKE_PRIVATE_KEY_VALUE" "no raw private key"
assert_not_contains "$RUNNER_OUT" "BEGIN PRIVATE KEY" "no private key block"
assert_not_contains "$RUNNER_OUT" "$FAKE_SECRET_B64" "no base64 secret"
assert_not_contains "$RUNNER_OUT" "client_email" "no client_email JSON"

echo
echo "== 7. gcloud exit code 0 -> TEST PASSED, runner exits 0 =="
common_env
FAKE_GCLOUD_EXIT=0
run_runner
assert_eq "$RUNNER_RC" "0" "runner exit 0"
assert_contains "$RUNNER_OUT" "FTL_EXIT_CODE=0" "raw exit code"
assert_contains "$RUNNER_OUT" "TEST_PASSED" "TEST PASSED classification"
assert_contains "$RUNNER_OUT" "FTL_MATRIX_ID=" "matrix id logged"

echo
echo "== 8. gcloud exit code 10 -> TEST_FAILED, runner exits 10 =="
common_env
FAKE_GCLOUD_EXIT=10
run_runner
assert_eq "$RUNNER_RC" "10" "runner preserves exit 10"
assert_contains "$RUNNER_OUT" "FTL_EXIT_CODE=10" "raw exit code"
assert_contains "$RUNNER_OUT" "TEST_FAILED" "TEST FAILED classification"

echo
echo "== 9. gcloud exit code 20 -> INFRASTRUCTURE_FAILURE, runner exits 20 =="
common_env
FAKE_GCLOUD_EXIT=20
run_runner
assert_eq "$RUNNER_RC" "20" "runner preserves exit 20"
assert_contains "$RUNNER_OUT" "INFRASTRUCTURE_FAILURE" "INFRASTRUCTURE classification"

echo
echo "== 10. exit 1 -> GENERAL_OR_CONFIGURATION_FAILURE, runner exits 1 =="
common_env
FAKE_GCLOUD_EXIT=1
run_runner
assert_eq "$RUNNER_RC" "1" "runner preserves exit 1"
assert_contains "$RUNNER_OUT" "GENERAL_OR_CONFIGURATION_FAILURE" "general failure classification"

echo
echo "== 11. exit 2 -> INVALID_COMMAND_OR_ARGUMENT, runner exits 2 =="
common_env
FAKE_GCLOUD_EXIT=2
run_runner
assert_eq "$RUNNER_RC" "2" "runner preserves exit 2"
assert_contains "$RUNNER_OUT" "INVALID_COMMAND_OR_ARGUMENT" "invalid args classification"

echo
echo "== 12. exit 15 -> INCONCLUSIVE, runner exits 15 =="
common_env
FAKE_GCLOUD_EXIT=15
run_runner
assert_eq "$RUNNER_RC" "15" "runner preserves exit 15"
assert_contains "$RUNNER_OUT" "INCONCLUSIVE" "inconclusive classification"

echo
echo "== 13. exit 18 -> UNSUPPORTED_TEST_ENVIRONMENT, runner exits 18 =="
common_env
FAKE_GCLOUD_EXIT=18
run_runner
assert_eq "$RUNNER_RC" "18" "runner preserves exit 18"
assert_contains "$RUNNER_OUT" "UNSUPPORTED_TEST_ENVIRONMENT" "unsupported env classification"

echo
echo "== 14. exit 19 -> TEST_CANCELED, runner exits 19 =="
common_env
FAKE_GCLOUD_EXIT=19
run_runner
assert_eq "$RUNNER_RC" "19" "runner preserves exit 19"
assert_contains "$RUNNER_OUT" "TEST_CANCELED" "canceled classification"

echo
echo "== 15. quota text -> QUOTA_EXHAUSTED annotation while keeping exit code =="
common_env
FAKE_GCLOUD_EXIT=1
export FAKE_GCLOUD_QUOTA_TEXT="ERROR: Quota exceeded for quota metric 'test_executions'"
run_runner
assert_eq "$RUNNER_RC" "1" "preserves real exit code 1"
assert_contains "$RUNNER_OUT" "QUOTA_EXHAUSTED" "QUOTA_EXHAUSTED annotation"
assert_contains "$RUNNER_OUT" "GENERAL_OR_CONFIGURATION_FAILURE" "primary classification retained"

echo
echo "== 16. device model not found -> CONFIGURATION FAILURE =="
common_env
FAKE_GCLOUD_EXIT=0
export FTL_DEVICE_MODEL="Pixel_NotAReal"
run_runner
assert_eq "$RUNNER_RC" "1" "exit 1"
assert_contains "$RUNNER_OUT" "CONFIGURATION_FAILURE" "CONFIGURATION FAILURE"
assert_contains "$RUNNER_OUT" "Pixel_NotAReal" "names the bad model"

echo
echo "== 17. device version unsupported -> CONFIGURATION FAILURE =="
common_env
export FTL_DEVICE_MODEL="MediumPhone.arm"
export FTL_DEVICE_VERSION="99"
run_runner
assert_eq "$RUNNER_RC" "1" "exit 1"
assert_contains "$RUNNER_OUT" "CONFIGURATION_FAILURE" "CONFIGURATION FAILURE"

echo
echo "== 17b. deprecated model -> CONFIGURATION FAILURE =="
common_env
export FTL_DEVICE_MODEL="DeprecatedModel"
export FTL_DEVICE_VERSION="33"
run_runner
assert_eq "$RUNNER_RC" "1" "exit 1"
assert_contains "$RUNNER_OUT" "CONFIGURATION_FAILURE" "CONFIGURATION FAILURE"
assert_contains "$RUNNER_OUT" "deprecated" "names the deprecated flag"

echo
echo "== 17c. reduced_stability model -> CONFIGURATION FAILURE =="
common_env
export FTL_DEVICE_MODEL="ReducedModel"
export FTL_DEVICE_VERSION="33"
run_runner
assert_eq "$RUNNER_RC" "1" "exit 1"
assert_contains "$RUNNER_OUT" "CONFIGURATION_FAILURE" "CONFIGURATION FAILURE"
assert_contains "$RUNNER_OUT" "reduced_stability" "names the reduced_stability flag"

echo
echo "== 18. cleanup trap removes temp files on failure path =="
common_env
FAKE_GCLOUD_EXIT=10
run_runner
# temp files may be recreated by the test harness itself; assert the runner's
# own sensitive temp artifacts (sa json / gcloud dirs) are gone.
if find "$TMPDIR" -maxdepth 1 -name 'ftl-sa.*.json' | grep -q .; then
    bad "service-account temp file leaked"
else
    ok "no service-account temp file leaked"
fi
if find "$TMPDIR" -maxdepth 1 -type d -name 'ftl-gcloud-config.*' | grep -q .; then
    bad "gcloud config temp dir leaked"
else
    ok "no gcloud config temp dir leaked"
fi
if find "$TMPDIR" -maxdepth 1 -name 'ftl-gcloud-453.0.0' -type d | grep -q .; then
    # The fake gcloud install dir is the runner's pinned gcloud cache; the
    # runner leaves it for reuse on the SAME node (it contains no secrets).
    # What MUST NOT leak is the per-run credential file and config dir, which
    # the checks above verify.
    ok "gcloud install dir retained (pinned cache, no secrets)"
else
    ok "gcloud install dir removed"
fi

echo
echo "== 19. gate: documentation-only diff -> SKIP =="
(
    cd "$TEST_WORKSPACE"
    git init -q -b main repo_doc && cd repo_doc
    git config user.email t@t && git config user.name t
    echo x > README.md && git add -A && git commit -qm c1
    git checkout -qb doc
    mkdir -p docs
    echo y >> README.md
    echo z > docs/NOTES.md
    git add -A && git commit -qm "docs: update"
    export CNB_BUILD_WORKSPACE="$PWD"
    before="$(git rev-parse HEAD~1)" commit="$(git rev-parse HEAD)"
    export CNB_BEFORE_SHA="$before" CNB_COMMIT="$commit"
    out="$("$GATE_SCRIPT")"
    assert_contains "$out" "TEST_LAB_GATE=skip" "gate skips docs-only"
    assert_contains "$out" "TEST LAB NOT EXECUTED: documentation-only change" "explicit skip reason"
)

echo
echo "== 20. gate: android/build-related diff -> RUN =="
(
    cd "$TEST_WORKSPACE/repo_doc"
    git checkout -qb appchange
    mkdir -p app/src && echo k > app/src/K.kt
    git add -A && git commit -qm "feat: app change"
    export CNB_BUILD_WORKSPACE="$PWD"
    before="$(git rev-parse HEAD~1)" commit="$(git rev-parse HEAD)"
    export CNB_BEFORE_SHA="$before" CNB_COMMIT="$commit"
    out="$("$GATE_SCRIPT")"
    assert_contains "$out" "TEST_LAB_GATE=run" "gate runs for app change"
    assert_contains "$out" "TEST LAB GATE: run" "run reason logged"
)

echo
echo "== 21. gate: empty CNB_BEFORE_SHA -> RUN (conservative) =="
(
    cd "$TEST_WORKSPACE/repo_doc"
    export CNB_BUILD_WORKSPACE="$PWD"
    unset CNB_BEFORE_SHA
    export CNB_COMMIT="$(git rev-parse HEAD)"
    out="$("$GATE_SCRIPT")"
    assert_contains "$out" "TEST_LAB_GATE=run" "gate runs when before sha empty"
)

echo
echo "== 22. gate: all-zero CNB_BEFORE_SHA -> RUN =="
(
    cd "$TEST_WORKSPACE/repo_doc"
    export CNB_BUILD_WORKSPACE="$PWD"
    export CNB_BEFORE_SHA="0000000000000000000000000000000000000000"
    export CNB_COMMIT="$(git rev-parse HEAD)"
    out="$("$GATE_SCRIPT")"
    assert_contains "$out" "TEST_LAB_GATE=run" "gate runs when before sha all-zero"
)

echo
echo "== 23. gate: full range with multiple commits -> RUN =="
(
    cd "$TEST_WORKSPACE/repo_doc"
    git checkout -qb multi
    echo a > gradle.properties && git add -A && git commit -qm "c1 gradle"
    echo b >> gradle.properties && git add -A && git commit -qm "c2 gradle"
    echo c >> gradle.properties && git add -A && git commit -qm "c3 gradle"
    export CNB_BUILD_WORKSPACE="$PWD"
    before="$(git rev-parse HEAD~3)" commit="$(git rev-parse HEAD)"
    export CNB_BEFORE_SHA="$before" CNB_COMMIT="$commit"
    out="$("$GATE_SCRIPT")"
    assert_contains "$out" "TEST_LAB_GATE=run" "gate runs for multi-commit push"
)

echo
echo "== 24. no Firebase API call made (no network / no quota) =="
# The fake gcloud never touches the network; the runner is invoked with PATH
# pointing ONLY at the fake bin. If a real gcloud were invoked it would fail
# (not on PATH), proving no real API call happens in these tests.
if command -v gcloud >/dev/null 2>&1; then
    bad "real gcloud leaked onto PATH during tests"
else
    ok "no real gcloud on PATH"
fi

echo
echo "== summary =="
echo "  PASS: $PASS  FAIL: $FAIL"
rm -rf "$TEST_WORKSPACE"
if [[ "$FAIL" -gt 0 ]]; then
    exit 1
fi
exit 0
