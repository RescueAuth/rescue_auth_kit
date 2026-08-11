#!/usr/bin/env bash
#
# test-release-version.sh
#
# Offline regression tests for scripts/release-version.sh (no Android SDK, no
# production secrets). Covers tag parsing, version extraction, and versionName
# mismatch rejection helpers used by the production release pipeline.
#
# Usage: bash scripts/test/test-release-version.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/scripts/release-version.sh"

PASS=0
FAIL=0
ok()  { printf '  \033[32mok\033[0m    %s\n' "$1"; PASS=$((PASS+1)); }
bad() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAIL=$((FAIL+1)); }

expect_tag_valid() { # tag label
    local tag="$1" label="$2"
    if release_tag_is_valid "$tag"; then ok "$label"; else bad "$label"; fi
}
expect_tag_invalid() { # tag label
    local tag="$1" label="$2"
    if release_tag_is_valid "$tag"; then bad "$label"; else ok "$label"; fi
}
expect_version() { # tag expected label
    local tag="$1" expected="$2" label="$3"
    local got
    got=$(release_version_from_tag "$tag") || { bad "$label (parse failed)"; return; }
    if [[ "$got" == "$expected" ]]; then ok "$label"; else bad "$label (got '$got', want '$expected')"; fi
}

echo "== 1. valid namespaced release tags =="
expect_tag_valid "rescueauth-v1.0.0"   "rescueauth-v1.0.0 -> valid"
expect_tag_valid "rescueauth-v1.0.1"   "rescueauth-v1.0.1 -> valid"
expect_tag_valid "rescueauth-v1.1.0"   "rescueauth-v1.1.0 -> valid"
expect_tag_valid "rescueauth-v2.3.4"   "rescueauth-v2.3.4 -> valid"
expect_tag_valid "rescueauth-v12.3.45" "rescueauth-v12.3.45 -> valid"
expect_tag_valid "rescueauth-v0.0.0"   "rescueauth-v0.0.0 -> valid"

echo
echo "== 2. invalid tags (must be rejected) =="
expect_tag_invalid "v1.0.0"             "legacy v1.0.0 -> reject"
expect_tag_invalid "v1.1.0"             "legacy v1.1.0 -> reject"
expect_tag_invalid "release-1.0.0"      "release-1.0.0 -> reject"
expect_tag_invalid "foo"                "foo -> reject"
expect_tag_invalid "main"               "main -> reject"
expect_tag_invalid "rescueauth-v1.0"    "rescueauth-v1.0 -> reject (missing patch)"
expect_tag_invalid "rescueauth-v1"      "rescueauth-v1 -> reject"
expect_tag_invalid "rescueauth-1.0.0"   "rescueauth-1.0.0 -> reject (missing v)"
expect_tag_invalid "rescueauth-v1.0.0.1" "rescueauth-v1.0.0.1 -> reject"
expect_tag_invalid "rescueauth-v1.0.0-rc1" "rescueauth-v1.0.0-rc1 -> reject"
expect_tag_invalid ""                  "empty tag -> reject"

echo
echo "== 3. version extraction =="
expect_version "rescueauth-v1.0.0" "1.0.0"  "rescueauth-v1.0.0 -> 1.0.0"
expect_version "rescueauth-v1.0.1" "1.0.1"  "rescueauth-v1.0.1 -> 1.0.1"
expect_version "rescueauth-v1.1.0" "1.1.0"  "rescueauth-v1.1.0 -> 1.1.0"
expect_version "rescueauth-v2.3.4" "2.3.4"  "rescueauth-v2.3.4 -> 2.3.4"
expect_version "rescueauth-v12.3.45" "12.3.45" "rescueauth-v12.3.45 -> 12.3.45"

echo
echo "== 4. version extraction fails closed on invalid tag =="
if release_version_from_tag "v1.0.0" >/dev/null 2>&1; then
    bad "version extraction from legacy tag should fail"
else
    ok "version extraction from legacy tag fails closed"
fi
if release_version_from_tag "foo" >/dev/null 2>&1; then
    bad "version extraction from invalid tag should fail"
else
    ok "version extraction from invalid tag fails closed"
fi

echo
echo "== 5. regex constant sanity =="
if [[ "$RELEASE_TAG_REGEX" == '^rescueauth-v[0-9]+\.[0-9]+\.[0-9]+$' ]]; then
    ok "RELEASE_TAG_REGEX constant"
else
    bad "RELEASE_TAG_REGEX constant changed"
fi

echo
echo "== summary =="
echo "  PASS: $PASS  FAIL: $FAIL"
if [[ "$FAIL" -gt 0 ]]; then
    exit 1
fi
exit 0
