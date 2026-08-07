#!/usr/bin/env bash
# shellcheck disable=SC2030,SC2031,SC2155
#
# test-check-test-lab-gate.sh
#
# Dedicated regression suite for scripts/check-test-lab-gate.sh.
#
# Proves (on a throwaway git repo shaped like this repository):
#   * every v2/ Gradle-project path + CI/runner self-change triggers RUN
#   * every pure-documentation path skips
#   * the REAL PR #13 changed path triggers RUN (it was wrongly skipped before
#     the strict v2/ scoping hotfix)
#   * root-level android/**, docs/** etc never match the anchored patterns
#
# Usage: bash scripts/test/test-check-test-lab-gate.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GATE_SCRIPT="$ROOT/scripts/check-test-lab-gate.sh"
TEST_WORKSPACE="$(mktemp -d)"
COUNTER_FILE="$TEST_WORKSPACE/counters"
: > "$COUNTER_FILE"

PASS=0
FAIL=0

ok()   { printf 'ok\n' >> "$COUNTER_FILE"; printf '  \033[32mok\033[0m    %s\n' "$1"; PASS=$((PASS+1)); }
bad()  { printf 'bad\n' >> "$COUNTER_FILE"; printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAIL=$((FAIL+1)); }

assert_contains() { # haystack needle label
    if [[ "$1" == *"$2"* ]]; then ok "$3"; else bad "$3 (missing '$2' in: $1)"; fi
}

# run the gate in a scratch repo after adding/committing <file>, expect <gate>
expect_gate() { # file gate label
    local file="$1" want="$2" label="$3"
    (
        cd "$TEST_WORKSPACE/repo"
        git checkout -qb "case-$(echo "$file" | tr '/ .' '___')-${RANDOM}" main
        mkdir -p "$(dirname "$file")"
        echo "change" >> "$file"
        git add -A
        git commit -qm "test: touch $file"
        export CNB_BUILD_WORKSPACE="$PWD"
        export CNB_BEFORE_SHA="$(git rev-parse HEAD~1)"
        export CNB_COMMIT="$(git rev-parse HEAD)"
        out="$("$GATE_SCRIPT")"
        if [[ "$want" == "run" ]]; then
            assert_contains "$out" "TEST_LAB_GATE=run" "$label"
        else
            assert_contains "$out" "TEST_LAB_GATE=skip" "$label"
        fi
        # The gate persists .cnb-gate.env in the workspace; remove it so it
        # never pollutes the scratch repo between cases.
        rm -f .cnb-gate.env
        git checkout -q main
        git clean -fdq
    )
}

# static checks -------------------------------------------------------------
if bash -n "$GATE_SCRIPT"; then ok "gate bash -n"; else bad "gate bash -n"; fi

# seed a scratch repo shaped like rescue_auth_kit (v2/ + root docs/android) ---
(
    cd "$TEST_WORKSPACE"
    git init -q -b main repo
    cd repo
    git config user.email t@t && git config user.name t
    mkdir -p docs v2/docs android/app v2/app/src/main \
             v2/app/src/test v2/app/src/androidTest v2/core v2/gradle/wrapper
    echo x > README.md
    echo x > docs/FIREBASE_TEST_LAB.md
    echo x > v2/docs/NOTES.md
    echo x > v2/app/src/main/Main.kt
    echo x > v2/app/src/test/UnitTest.kt
    echo x > v2/app/src/androidTest/InstrumentedTest.kt
    echo x > v2/core/Core.kt
    echo x > v2/gradle.properties
    echo x > v2/build.gradle.kts
    echo x > v2/settings.gradle.kts
    echo x > v2/gradle/wrapper/gradle-wrapper.properties
    echo x > v2/gradle/libs.versions.toml
    echo x > android/app/build.gradle.kts
    git add -A
    git commit -qm seed
)

echo
echo "== must RUN (v2/ Gradle project + CI/runner self-changes) =="
expect_gate "v2/app/src/main/kotlin/com/rescueauth/v2/database/Db.kt" run "v2/app/src/main/** -> run"
expect_gate "v2/app/src/test/kotlin/com/rescueauth/v2/database/DbUnitTest.kt" run "v2/app/src/test/** -> run"
expect_gate "v2/app/src/androidTest/kotlin/com/rescueauth/v2/database/RescueAuthDatabaseInstrumentedTest.kt" run "v2/app/src/androidTest/** -> run (PR #13 real path)"
expect_gate "v2/app/src/main/res/values/strings.xml" run "v2/app/ deep resource -> run"
expect_gate "v2/core/src/main/kotlin/com/rescueauth/v2/core/Core.kt" run "v2/core/** -> run"
expect_gate "v2/gradle/wrapper/gradle-wrapper.properties" run "v2/gradle/** (wrapper) -> run"
expect_gate "v2/gradle/libs.versions.toml" run "version catalog (v2/gradle/libs.versions.toml) -> run"
expect_gate "v2/build.gradle" run "v2/build.gradle (Groovy DSL) -> run"
expect_gate "v2/build.gradle.kts" run "v2/build.gradle.kts -> run"
expect_gate "v2/settings.gradle" run "v2/settings.gradle -> run"
expect_gate "v2/settings.gradle.kts" run "v2/settings.gradle.kts -> run"
expect_gate "v2/gradle.properties" run "v2/gradle.properties -> run"
expect_gate "v2/gradlew" run "v2/gradlew -> run"
expect_gate "v2/gradlew.bat" run "v2/gradlew.bat -> run"
expect_gate ".cnb.yml" run ".cnb.yml (CI self-change) -> run"
expect_gate "scripts/run-firebase-test-lab.sh" run "Test Lab runner self-change -> run"
expect_gate "scripts/check-test-lab-gate.sh" run "gate script self-change -> run"

echo
echo "== must SKIP (pure documentation / non-v2) =="
expect_gate "docs/FIREBASE_TEST_LAB.md" skip "docs/** -> skip"
expect_gate "README.md" skip "README* -> skip"
expect_gate "README.zh-CN.md" skip "README.zh-CN.md -> skip"
expect_gate "v2/docs/PHASE1_REPORT.md" skip "v2/docs/** -> skip"
expect_gate "v2/PRODUCT.md" skip "v2/*.md -> skip"
expect_gate "CHANGELOG.md" skip "CHANGELOG.md -> skip"
expect_gate "android/app/src/main/kotlin/com/xincy/rescue_auth_kit/MainActivity.kt" skip "root android/** (Flutter host) -> skip"
expect_gate "android/build.gradle.kts" skip "root android/build.gradle.kts -> skip"
expect_gate "gradle.properties" skip "root gradle.properties -> skip (not v2/)"
expect_gate "libs.versions.toml" skip "root libs.versions.toml -> skip (not v2/gradle/)"
expect_gate "app/src/main/Main.kt" skip "root app/ -> skip (not v2/app/)"
expect_gate "core/Core.kt" skip "root core/ -> skip (not v2/core/)"

echo
echo "== conservative fallbacks still RUN =="
(
    cd "$TEST_WORKSPACE/repo"
    export CNB_BUILD_WORKSPACE="$PWD"
    unset CNB_BEFORE_SHA
    export CNB_COMMIT="$(git rev-parse HEAD)"
    out="$("$GATE_SCRIPT")"
    assert_contains "$out" "TEST_LAB_GATE=run" "empty CNB_BEFORE_SHA -> run"
)
(
    cd "$TEST_WORKSPACE/repo"
    export CNB_BUILD_WORKSPACE="$PWD"
    export CNB_BEFORE_SHA="0000000000000000000000000000000000000000"
    export CNB_COMMIT="$(git rev-parse HEAD)"
    out="$("$GATE_SCRIPT")"
    assert_contains "$out" "TEST_LAB_GATE=run" "all-zero CNB_BEFORE_SHA -> run"
)

echo
echo "== summary =="
PASS="$(grep -c '^ok$' "$COUNTER_FILE" || true)"
FAIL="$(grep -c '^bad$' "$COUNTER_FILE" || true)"
echo "  PASS: $PASS  FAIL: $FAIL"
rm -rf "$TEST_WORKSPACE"
if [[ "$FAIL" -gt 0 ]]; then
    exit 1
fi
exit 0
