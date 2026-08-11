#!/usr/bin/env bash
#
# test-build-debug-apk.sh
#
# Offline static checks for scripts/build-debug-apk.sh (no Android SDK needed).
# Verifies the DEBUG pipeline contract:
#   * the debug script does NOT reference any production Secret Repo / signing
#     secrets (RESCUEAUTH_KEYSTORE_BASE64 / *_PASSWORD / validateReleaseSigning),
#   * the artifact-naming template produces RescueAuth-<versionName>-debug-<sha>.apk.
#
# Usage: bash scripts/test/test-build-debug-apk.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT/scripts/build-debug-apk.sh"

PASS=0
FAIL=0
ok()  { printf '  \033[32mok\033[0m    %s\n' "$1"; PASS=$((PASS+1)); }
bad() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAIL=$((FAIL+1)); }

echo "== static checks =="
if bash -n "$SCRIPT"; then ok "script bash -n"; else bad "script bash -n"; fi

echo
echo "== 1. debug script must NOT touch production signing secrets =="
for token in \
  RESCUEAUTH_KEYSTORE_BASE64 \
  RESCUEAUTH_STORE_PASSWORD \
  RESCUEAUTH_KEY_PASSWORD \
  RESCUEAUTH_KEY_ALIAS \
  rescue_auth_kit_secrets \
  validateReleaseSigning \
  assembleRelease \
  android-signing.yml; do
  if grep -Fq "$token" "$SCRIPT"; then
    bad "debug script must not reference '$token'"
  else
    ok "debug script does not reference '$token'"
  fi
done

echo
echo "== 2. debug script uses debug signing + assembleDebug =="
if grep -Fq ":app:assembleDebug" "$SCRIPT"; then ok "uses :app:assembleDebug"; else bad "missing :app:assembleDebug"; fi

echo
echo "== 3. artifact naming template =="
# The naming template must include the literal versionName, 'debug', and short commit.
if grep -Fq 'RescueAuth-${version_name}-debug-${short_commit}.apk' "$SCRIPT"; then
  ok "artifact template RescueAuth-<versionName>-debug-<shortCommit>.apk present"
else
  bad "artifact template missing"
fi

echo
echo "== 4. debuggable assertion =="
if grep -Fq '"$debuggable" == "true"' "$SCRIPT"; then ok "asserts debuggable=true"; else bad "missing debuggable=true assertion"; fi

echo
echo "== summary =="
echo "  PASS: $PASS  FAIL: $FAIL"
if [[ "$FAIL" -gt 0 ]]; then
    exit 1
fi
exit 0
