#!/usr/bin/env bash
# shellcheck disable=SC2030,SC2031,SC2155
#
# test-check-test-lab-gate.sh
#
# Dedicated regression suite for scripts/check-test-lab-gate.sh.
#
# Proves (on a throwaway git repo shaped like this repository):
#   * every Gradle-project path (app/**, core/**, gradle/**, build files) +
#     CI/runner self-change triggers RUN
#   * every pure-documentation path skips
#   * the REAL PR #13 changed path triggers RUN (it was wrongly skipped before
#     the strict scoping hotfix)
#   * docs/**, tools/**, README* etc never match the anchored patterns
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

# seed a scratch repo shaped like rescue_auth_kit (Gradle project at root +
# docs/tools/README at root) ---
(
    cd "$TEST_WORKSPACE"
    git init -q -b main repo
    cd repo
    git config user.email t@t && git config user.name t
    mkdir -p docs tools app/src/main \
             app/src/test app/src/androidTest core gradle/wrapper
    echo x > README.md
    echo x > docs/FIREBASE_TEST_LAB.md
    echo x > tools/NOTES.md
    echo x > app/src/main/Main.kt
    echo x > app/src/test/UnitTest.kt
    echo x > app/src/androidTest/InstrumentedTest.kt
    echo x > core/Core.kt
    echo x > gradle.properties
    echo x > build.gradle.kts
    echo x > settings.gradle.kts
    echo x > gradle/wrapper/gradle-wrapper.properties
    echo x > gradle/libs.versions.toml
    echo x > gradlew
    echo x > gradlew.bat
    git add -A
    git commit -qm seed
)

echo
echo "== must RUN (Gradle project + CI/runner self-changes) =="
expect_gate "app/src/main/kotlin/com/rescueauth/v2/database/Db.kt" run "app/src/main/** -> run"
expect_gate "app/src/test/kotlin/com/rescueauth/v2/database/DbUnitTest.kt" run "app/src/test/** -> run"
expect_gate "app/src/androidTest/kotlin/com/rescueauth/v2/database/RescueAuthDatabaseInstrumentedTest.kt" run "app/src/androidTest/** -> run (PR #13 real path)"
expect_gate "app/src/main/res/values/strings.xml" run "app/ deep resource -> run"
expect_gate "core/src/main/kotlin/com/rescueauth/v2/core/Core.kt" run "core/** -> run"
expect_gate "gradle/wrapper/gradle-wrapper.properties" run "gradle/** (wrapper) -> run"
expect_gate "gradle/libs.versions.toml" run "version catalog (gradle/libs.versions.toml) -> run"
expect_gate "build.gradle" run "build.gradle (Groovy DSL) -> run"
expect_gate "build.gradle.kts" run "build.gradle.kts -> run"
expect_gate "settings.gradle" run "settings.gradle -> run"
expect_gate "settings.gradle.kts" run "settings.gradle.kts -> run"
expect_gate "gradle.properties" run "gradle.properties -> run"
expect_gate "gradlew" run "gradlew -> run"
expect_gate "gradlew.bat" run "gradlew.bat -> run"
expect_gate ".cnb.yml" run ".cnb.yml (CI self-change) -> run"
expect_gate "scripts/run-firebase-test-lab.sh" run "Test Lab runner self-change -> run"
expect_gate "scripts/check-test-lab-gate.sh" run "gate script self-change -> run"

echo
echo "== must SKIP (pure documentation / non-Gradle) =="
expect_gate "docs/FIREBASE_TEST_LAB.md" skip "docs/** -> skip"
expect_gate "README.md" skip "README* -> skip"
expect_gate "README.zh-CN.md" skip "README.zh-CN.md -> skip"
expect_gate "docs/PHASE1_REPORT.md" skip "docs/** -> skip"
expect_gate "PRODUCT.md" skip "*.md at root -> skip"
expect_gate "AGENTS.md" skip "AGENTS.md -> skip"
expect_gate "tools/interop-fixture/README.md" skip "tools/** -> skip"
expect_gate "legacy-fixtures/manifest.json" skip "legacy-fixtures/** -> skip"
expect_gate "release/android-signing-certificate.txt" skip "release/** -> skip"

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
