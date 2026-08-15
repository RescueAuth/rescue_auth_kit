#!/usr/bin/env bash
#
# check-test-lab-gate.sh
#
# Decides whether a `main` push should submit a Firebase Test Lab matrix,
# based on the FULL changed-file range `CNB_BEFORE_SHA..CNB_COMMIT`.
#
# Rules (see docs/FIREBASE_TEST_LAB.md):
#   * If CNB_BEFORE_SHA is empty/invalid/all-zero  -> RUN (conservative).
#   * If the changed-file range is empty           -> RUN (conservative).
#   * If NO change touches the trigger paths below -> SKIP with an explicit
#     "TEST LAB NOT EXECUTED: documentation-only change" reason.
#   * Never skips based on arbitrary strings in commit messages.
#   * Multiple commits in one push are covered because we compare the FULL
#     range, not just the last commit.
#
# Trigger paths that require Test Lab (STRICTLY scoped to the Gradle
# project + the CI/Test Lab runner itself; paths are anchored so docs/**,
# README* and the legacy web/ tools can never match):
#   app/**
#   core/**
#   gradle/**                     (wrapper + gradle/libs.versions.toml)
#   build.gradle                  (kept for future Groovy DSL)
#   build.gradle.kts
#   settings.gradle               (kept for future Groovy DSL)
#   settings.gradle.kts
#   gradle.properties
#   gradlew                       (wrapper launcher, build infra)
#   gradlew.bat
#   .cnb.yml
#   scripts/run-firebase-test-lab.sh
#   scripts/check-test-lab-gate.sh
#
# Anything else (docs/**, README*, tools/**, legacy-fixtures/**, ...) is
# treated as documentation-only and skipped.
#
# Output:
#   * prints TEST_LAB_GATE=run|skip and TEST_LAB_SKIP_REASON=...
#   * writes the same two keys into <workspace>/.cnb-gate.env for the Test Lab
#     stage to consume.
#
# Usage:
#   CNB_BEFORE_SHA=<sha> CNB_COMMIT=<sha> scripts/check-test-lab-gate.sh

set -euo pipefail

workspace="${CNB_BUILD_WORKSPACE:-/workspace}"
before_sha="${CNB_BEFORE_SHA:-}"
commit_sha="${CNB_COMMIT:-}"

# Normalize an all-zero / missing sha to "unset".
is_unset_sha() {
    [[ -z "$1" ]] || [[ "$1" =~ ^0+$ ]]
}

declare_gate() {
    local gate="$1"
    local reason="$2"
    printf 'TEST_LAB_GATE=%s\n' "$gate"
    printf 'TEST_LAB_SKIP_REASON=%s\n' "$reason"
    # Persist for the Test Lab stage (same pipeline, same build env).
    : > "${workspace}/.cnb-gate.env"
    printf 'TEST_LAB_GATE=%s\n' "$gate" >> "${workspace}/.cnb-gate.env"
    printf 'TEST_LAB_SKIP_REASON=%s\n' "$reason" >> "${workspace}/.cnb-gate.env"
}

main() {
    if is_unset_sha "$before_sha"; then
        declare_gate "run" ""
        echo "TEST LAB GATE: run (CNB_BEFORE_SHA empty/invalid/all-zero)"
        return 0
    fi
    if [[ -z "$commit_sha" ]]; then
        declare_gate "run" ""
        echo "TEST LAB GATE: run (CNB_COMMIT empty)"
        return 0
    fi
    if ! git -C "$workspace" cat-file -e "${before_sha}^{commit}" 2>/dev/null; then
        declare_gate "run" ""
        echo "TEST LAB GATE: run (CNB_BEFORE_SHA not a valid commit)"
        return 0
    fi

    # Full range diff. The `..` form fails when the two commits are unrelated,
    # so we fall back to the two-commit form; either way we cover ALL commits
    # in this push.
    local changed_files
    changed_files="$(git -C "$workspace" diff --name-only "${before_sha}..${commit_sha}" 2>/dev/null \
        || git -C "$workspace" diff --name-only "${before_sha}" "${commit_sha}" 2>/dev/null \
        || true)"
    if [[ -z "$changed_files" ]]; then
        declare_gate "run" ""
        echo "TEST LAB GATE: run (no changed files resolved; conservative default)"
        return 0
    fi

    # Strict scoping. Every alternative is anchored so only the Gradle
    # project tree, the CI config and the Test Lab runner/gate scripts match.
    # This is what guarantees e.g. app/src/androidTest/** (PR #13) triggers
    # while docs/**, README* and tools/** stay skip.
    if ! grep -qE '^(app/|core/|gradle/|build\.gradle$|build\.gradle\.kts$|settings\.gradle$|settings\.gradle\.kts$|gradle\.properties$|gradlew$|gradlew\.bat$|\.cnb\.yml$|scripts/run-firebase-test-lab\.sh$|scripts/check-test-lab-gate\.sh$)' <<<"$changed_files"; then
        declare_gate "skip" "TEST LAB NOT EXECUTED: documentation-only change"
        echo "TEST LAB NOT EXECUTED: documentation-only change"
        return 0
    fi

    declare_gate "run" ""
    echo "TEST LAB GATE: run"
    return 0
}

main "$@"
