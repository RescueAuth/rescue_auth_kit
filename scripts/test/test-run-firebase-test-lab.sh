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
    if [[ -n "${FAKE_GCLOUD_LOG:-}" ]]; then
        printf 'describe %s\n' "$*" >> "$FAKE_GCLOUD_LOG"
    fi
    # Real gcloud `models describe` defaults to YAML and only emits JSON when
    # --format=json is passed. The runner's parser uses json.load(), so the
    # flag is mandatory. The fake mirrors this faithfully: without the flag it
    # prints YAML (which the parser cannot read), turning every existing
    # happy-path test into a live guard for the --format=json flag.
    if [[ "${7:-}" != "--format=json" ]]; then
        printf 'form: VIRTUAL\nid: %s\nname: %s\nsupportedVersionIds:\n- "30"\n- "31"\n- "33"\ntags:\n- arm\n' "${6:-}" "${6:-}"
        exit 0
    fi
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
    if [[ -n "${FAKE_GCLOUD_LOG:-}" ]]; then printf 'run %s\n' "$*" >> "$FAKE_GCLOUD_LOG"; fi
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

# Realistic APK-shaped archive: AndroidManifest.xml FIRST (as Gradle produces),
# followed by many other entries so unzip still has data to stream AFTER grep -q
# would have exited on the first match. This is the archive shape that triggers
# the SIGPIPE false negative in `unzip -Z1 ... | grep -q ...` under pipefail.
mkfake_apk_large() {
    local path="$1"
    local nfiles="$2"
    mkdir -p "$(dirname "$path")"
    rm -f "$path"
    (
        cd "$(mktemp -d)"
        touch AndroidManifest.xml
        local i
        for i in $(seq 1 "$nfiles"); do
            printf 'x%.0s' $(seq 1 100) > "filler_${i}.bin"
        done
        zip -q -r "$path" .
    )
}

# Valid zip archive WITHOUT AndroidManifest.xml (must be rejected).
mkfake_apk_nomanifest() {
    local path="$1"
    mkdir -p "$(dirname "$path")"
    rm -f "$path"
    ( cd "$(mktemp -d)" && touch classes.dex resources.arsc && zip -q -r "$path" . )
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
export FAKE_GCLOUD_LOG="$TEST_WORKSPACE/output-dir-args.log"
run_runner
assert_eq "$RUNNER_RC" "0" "runner exit 0"
assert_contains "$RUNNER_OUT" "FTL_EXIT_CODE=0" "raw exit code"
assert_contains "$RUNNER_OUT" "TEST_PASSED" "TEST PASSED classification"
assert_contains "$RUNNER_OUT" "FTL_MATRIX_ID=" "matrix id logged"
assert_contains "$(cat "$FAKE_GCLOUD_LOG")" "--environment-variables=additionalTestOutputDir=/sdcard/Android/data/com.rescueauth.v2/files/test-output" "instrumentation screenshot output directory configured"
assert_contains "$(cat "$FAKE_GCLOUD_LOG")" "--directories-to-pull=/sdcard/Android/data/com.rescueauth.v2/files/test-output" "screenshot evidence collected from same directory"
unset FAKE_GCLOUD_LOG

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
echo "== 20. gate: app/main build-related diff -> RUN =="
(
    cd "$TEST_WORKSPACE/repo_doc"
    git checkout -qb appchange
    mkdir -p app/src/main && echo k > app/src/main/K.kt
    git add -A && git commit -qm "feat: app change"
    export CNB_BUILD_WORKSPACE="$PWD"
    before="$(git rev-parse HEAD~1)" commit="$(git rev-parse HEAD)"
    export CNB_BEFORE_SHA="$before" CNB_COMMIT="$commit"
    out="$("$GATE_SCRIPT")"
    assert_contains "$out" "TEST_LAB_GATE=run" "gate runs for app/main change"
    assert_contains "$out" "TEST LAB GATE: run" "run reason logged"
)

# docs/ must NOT trigger Test Lab.
(
    cd "$TEST_WORKSPACE/repo_doc"
    git checkout -qb rootdocschange
    mkdir -p docs && echo k > docs/NOTES.md
    git add -A && git commit -qm "feat: docs change"
    export CNB_BUILD_WORKSPACE="$PWD"
    before="$(git rev-parse HEAD~1)" commit="$(git rev-parse HEAD)"
    export CNB_BEFORE_SHA="$before" CNB_COMMIT="$commit"
    out="$("$GATE_SCRIPT")"
    assert_contains "$out" "TEST_LAB_GATE=skip" "gate skips docs change"
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

# PR #13 regression: the REAL path that was wrongly skipped. A change under
# app/src/androidTest/ MUST run Test Lab (it is instrumentation code).
(
    cd "$TEST_WORKSPACE/repo_doc"
    git checkout -qb pr13
    mkdir -p app/src/androidTest/kotlin/com/rescueauth/v2/database
    echo x > app/src/androidTest/kotlin/com/rescueauth/v2/database/RescueAuthDatabaseInstrumentedTest.kt
    git add -A && git commit -qm "fix: make androidTest @Test methods JVM void"
    export CNB_BUILD_WORKSPACE="$PWD"
    before="$(git rev-parse HEAD~1)" commit="$(git rev-parse HEAD)"
    export CNB_BEFORE_SHA="$before" CNB_COMMIT="$commit"
    out="$("$GATE_SCRIPT")"
    assert_contains "$out" "TEST_LAB_GATE=run" "PR #13 real androidTest path triggers run"
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
echo "== 25. SIGPIPE regression: manifest-first APK with many trailing entries must PASS =="
# Regression for the firebase-test-lab stage failure:
#   [ftl][ERROR] APP_APK does not look like an Android APK (no AndroidManifest.xml)
# The old check `unzip -Z1 "$apk" | grep -q 'AndroidManifest.xml'` under
# `set -euo pipefail` kills the producer with SIGPIPE (141) as soon as grep -q
# exits, so pipefail fails the whole pipeline even though the manifest exists.
# The runner runs under `set -euo pipefail` (line 27), so a manifest-first,
# many-entry APK is exactly the shape that used to be misjudged as "not an APK".
common_env
mkfake_apk_large "$APP_APK" 400
mkfake_apk "$TEST_APK"
FAKE_GCLOUD_EXIT=0
run_runner
assert_eq "$RUNNER_RC" "0" "runner exit 0 for manifest-first large APK"
assert_contains "$RUNNER_OUT" "APP_APK ok" "APP_APK passes validation"
assert_contains "$RUNNER_OUT" "TEST_APK ok" "TEST_APK passes validation"
assert_contains "$RUNNER_OUT" "TEST_PASSED" "reaches fake Test Lab and passes"

# Show the root cause explicitly: the OLD pattern really does exit 141 here,
# while the archive undeniably contains AndroidManifest.xml at its root.
(
    set +e
    set -o pipefail
    unzip -Z1 "$APP_APK" 2>/dev/null | grep -q '^AndroidManifest.xml$'
    ps=( "${PIPESTATUS[@]}" )
    # Under pipefail the pipeline's overall status is the rightmost non-zero
    # element of PIPESTATUS (capturing $? would reset the array first).
    old_rc=0
    for c in "${ps[@]}"; do [[ "$c" != 0 ]] && old_rc="$c"; done
    assert_eq "$old_rc" "141" "old pattern: overall rc=141 under pipefail (SIGPIPE)"
    assert_eq "${ps[0]}" "141" "old pattern: unzip producer killed by SIGPIPE (141)"
    assert_eq "${ps[1]}" "0" "old pattern: grep found the manifest (0)"
)
unzip -Z1 "$APP_APK" 2>/dev/null > "$TEST_WORKSPACE/large-listing.txt"
if grep -q '^AndroidManifest.xml$' "$TEST_WORKSPACE/large-listing.txt"; then
    ok "archive really contains root AndroidManifest.xml"
else
    bad "archive really contains root AndroidManifest.xml"
fi

echo
echo "== 26. valid zip WITHOUT AndroidManifest.xml must FAIL fast =="
common_env
mkfake_apk_nomanifest "$APP_APK"
mkfake_apk "$TEST_APK"
run_runner
assert_eq "$RUNNER_RC" "1" "exit 1"
assert_contains "$RUNNER_OUT" "CONFIGURATION_FAILURE" "status CONFIGURATION_FAILURE"
assert_contains "$RUNNER_OUT" "does not look like an Android APK" "reports missing AndroidManifest.xml"
assert_contains "$RUNNER_OUT" "APP_APK" "names APP_APK"

# TEST_APK goes through the SAME validate_apk(); verify it is covered too.
common_env
mkfake_apk "$APP_APK"
mkfake_apk_nomanifest "$TEST_APK"
run_runner
assert_eq "$RUNNER_RC" "1" "exit 1"
assert_contains "$RUNNER_OUT" "CONFIGURATION_FAILURE" "TEST_APK status CONFIGURATION_FAILURE"
assert_contains "$RUNNER_OUT" "does not look like an Android APK" "TEST_APK reports missing manifest"
assert_contains "$RUNNER_OUT" "TEST_APK" "names TEST_APK"

echo
echo "== 27. set -euo pipefail: legal APK is NOT misjudged via SIGPIPE =="
# The runner itself runs under `set -euo pipefail`; exercising the new helper
# directly (same archive shape as test 25) proves a legal APK never triggers
# the old 141 false negative.
common_env
mkfake_apk_large "$APP_APK" 400
(
    set -euo pipefail
    # Extract the actual helper from the runner (avoids sourcing, which would
    # execute main()).
    apk_has_manifest=$(awk '/^apk_has_manifest\(\) \{/{f=1} f{print} f && /^\}$/{exit}' "$RUNNER")
    if bash -c "set -euo pipefail; $apk_has_manifest; apk_has_manifest \"$APP_APK\""; then
        ok "apk_has_manifest PASSES legal manifest-first APK under set -euo pipefail"
    else
        bad "apk_has_manifest PASSES legal manifest-first APK under set -euo pipefail"
    fi
)
mkfake_apk_nomanifest "$APP_APK"
(
    set -euo pipefail
    apk_has_manifest=$(awk '/^apk_has_manifest\(\) \{/{f=1} f{print} f && /^\}$/{exit}' "$RUNNER")
    if bash -c "set -euo pipefail; $apk_has_manifest; ! apk_has_manifest \"$APP_APK\""; then
        ok "apk_has_manifest FAILS manifest-less zip under set -euo pipefail"
    else
        bad "apk_has_manifest FAILS manifest-less zip under set -euo pipefail"
    fi
)

echo

echo "== 28. catalog parser regression: multi-value supportedVersionIds + --format=json =="
# Root cause of the main-CI failure:
#   [ftl][ERROR] FTL device version not supported for model MediumPhone.arm: API 33
#
# Real `gcloud firebase test android models describe` defaults to YAML and only
# emits JSON with --format=json. The runner's parser calls json.load() on the
# captured file, so without the flag the catalog "30,31,33" is unreadable and
# EVERY version looks unsupported. The fix adds --format=json to the describe
# call. These regression cases pin the multi-value supportedVersionIds member-
# ship behaviour with the runner's real parser.
common_env
FAKE_GCLOUD_EXIT=0

# 28a. happy path (MediumPhone.arm + API 33, multi-value supportedVersionIds)
#      already covered by tests 7..15, but assert explicitly that the runner
#      passed --format=json to the fake gcloud.
FAKE_GCLOUD_LOG="$TEST_WORKSPACE/gcloud-calls.log"
export FAKE_GCLOUD_LOG
run_runner
assert_eq "$RUNNER_RC" "0" "28a runner exit 0 for MediumPhone.arm API 33"
assert_contains "$RUNNER_OUT" "FTL device catalog check passed" "28a catalog check passes"
if grep -q 'describe.*MediumPhone.arm.*--format=json' "$FAKE_GCLOUD_LOG"; then
    ok "28a fake gcloud describe invoked WITH --format=json"
else
    bad "28a fake gcloud describe invoked WITH --format=json (got: $(cat "$FAKE_GCLOUD_LOG" 2>/dev/null))"
fi
unset FAKE_GCLOUD_LOG

# 28b. multi-value supportedVersionIds: version 30 and 31 must ALSO be accepted
#      (same JSON list, membership check is not a single-element special case).
for v in 30 31; do
    common_env
    export FTL_DEVICE_VERSION="$v"
    FAKE_GCLOUD_EXIT=0
    run_runner
    assert_eq "$RUNNER_RC" "0" "28b runner exit 0 for MediumPhone.arm API $v"
    assert_contains "$RUNNER_OUT" "FTL device catalog check passed" "28b catalog check passes for API $v"
done

# 28c. version NOT in the multi-value list must still fail fast (membership is
#      not inverted by the JSON fix).
common_env
FAKE_GCLOUD_EXIT=0
export FTL_DEVICE_VERSION="29"
run_runner
assert_eq "$RUNNER_RC" "1" "28c exit 1 for unsupported API 29"
assert_contains "$RUNNER_OUT" "CONFIGURATION_FAILURE" "28c CONFIGURATION_FAILURE"
assert_contains "$RUNNER_OUT" "FTL device version not supported for model MediumPhone.arm: API 29" "28c names model and version"

# 28d. parser unit-level guard: the runner's exact python membership expression
#      must return "yes" for 33 and "no" for an absent value against a JSON
#      doc with multi-value supportedVersionIds (30/31/33).
cat > "$TEST_WORKSPACE/multi-version.json" <<'EOF'
{"form":"virtual","supportedVersionIds":["30","31","33"],"tags":["arm"]}
EOF
for probe in 33 31 30; do
    got="$(python3 -c '
import json, sys
d = json.load(open(sys.argv[1]))
versions = d.get("supportedVersionIds", []) or []
print("yes" if sys.argv[2] in [str(v) for v in versions] else "no")
' "$TEST_WORKSPACE/multi-version.json" "$probe")"
    assert_eq "$got" "yes" "28d parser: API $probe is a member of multi-value list"
done
got="$(python3 -c '
import json, sys
d = json.load(open(sys.argv[1]))
versions = d.get("supportedVersionIds", []) or []
print("yes" if sys.argv[2] in [str(v) for v in versions] else "no")
' "$TEST_WORKSPACE/multi-version.json" "29")"
assert_eq "$got" "no" "28d parser: absent API 29 is not a member"

# 28e. YAML default (no --format=json) must NOT be accepted by the parser:
#      guards against re-introducing the bug even if a future change drops the
#      flag.
cat > "$TEST_WORKSPACE/yaml-default.yaml" <<'EOF'
form: VIRTUAL
id: MediumPhone.arm
name: MediumPhone.arm
supportedVersionIds:
- '30'
- '31'
- '33'
tags:
- arm
EOF
bad_rc=0
python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$TEST_WORKSPACE/yaml-default.yaml" 2>/dev/null \
    || bad_rc=$?
assert_eq "$bad_rc" "1" "28e YAML default output is rejected by json.load (bug guard)"

echo

echo "== summary =="
echo "  PASS: $PASS  FAIL: $FAIL"
rm -rf "$TEST_WORKSPACE"
if [[ "$FAIL" -gt 0 ]]; then
    exit 1
fi
exit 0
