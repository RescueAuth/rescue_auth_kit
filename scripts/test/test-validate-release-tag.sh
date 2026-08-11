#!/usr/bin/env bash
#
# test-validate-release-tag.sh
#
# Offline regression tests for scripts/validate-release-tag.sh (no Android SDK,
# no production secrets). Confirms the production tag gate fails closed on
# legacy/unprefixed tags and passes for valid namespaced release tags.
#
# Usage: bash scripts/test/test-validate-release-tag.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GATE="$ROOT/scripts/validate-release-tag.sh"

PASS=0
FAIL=0
ok()  { printf '  \033[32mok\033[0m    %s\n' "$1"; PASS=$((PASS+1)); }
bad() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAIL=$((FAIL+1)); }

expect_version() { # tag expected label
    local tag="$1" expected="$2" label="$3"
    local got
    got=$(bash "$GATE" "$tag") && rc=0 || rc=1
    if [[ $rc -ne 0 ]]; then bad "$label (gate failed)"; return; fi
    if [[ "$got" == "$expected" ]]; then ok "$label"; else bad "$label (got '$got', want '$expected')"; fi
}

expect_reject() { # tag label
    local tag="$1" label="$2"
    local out
    out=$(bash "$GATE" "$tag" 2>&1) && rc=0 || rc=1
    if [[ $rc -ne 0 ]]; then ok "$label"; else bad "$label (gate unexpectedly passed)"; fi
}

echo "== 1. valid tags pass and print version =="
expect_version "rescueauth-v1.0.0" "1.0.0" "rescueauth-v1.0.0 -> PASS"
expect_version "rescueauth-v1.0.1" "1.0.1" "rescueauth-v1.0.1 -> PASS"
expect_version "rescueauth-v2.3.4" "2.3.4" "rescueauth-v2.3.4 -> PASS"
expect_version "rescueauth-v12.3.45" "12.3.45" "rescueauth-v12.3.45 -> PASS"

echo
echo "== 2. legacy / invalid tags fail closed =="
expect_reject "legacy-v1.0.0"        "legacy-v1.0.0 -> FAIL CLOSED"
expect_reject "legacy-v1.2.0"        "legacy-v1.2.0 -> FAIL CLOSED"
expect_reject "v1.0.0"            "legacy v1.0.0 -> FAIL CLOSED"
expect_reject "v1.1.0"            "legacy v1.1.0 -> FAIL CLOSED"
expect_reject "v1.2.0"            "legacy v1.2.0 -> FAIL CLOSED"
expect_reject "release-1.0.0"     "release-1.0.0 -> FAIL CLOSED"
expect_reject "foo"               "foo -> FAIL CLOSED"
expect_reject "main"              "main -> FAIL CLOSED"
expect_reject "rescueauth-v1.0"   "rescueauth-v1.0 -> FAIL CLOSED"
expect_reject ""                  "empty tag -> FAIL CLOSED"

echo
echo "== summary =="
echo "  PASS: $PASS  FAIL: $FAIL"
if [[ "$FAIL" -gt 0 ]]; then
    exit 1
fi
exit 0
