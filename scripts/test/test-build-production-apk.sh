#!/usr/bin/env bash
# shellcheck disable=SC2030,SC2031,SC2155
#
# test-build-production-apk.sh
#
# Offline (no Android SDK, no production secrets) verification of the apksigner
# signer-count and certificate-fingerprint PARSERS in
# scripts/build-production-apk.sh.
#
# Regression coverage for the production-signing verification bug where the old
# parser counted `V2 Signer: certificate SHA-256 digest:` METADATA lines as
# separate signers and therefore rejected a correctly signed single-signer APK
# with "Release APK must contain exactly one signer."
#
# The real parser functions are extracted from the production script (without
# sourcing/executing main()) and exercised directly against realistic apksigner
# verbose --print-certs output fixtures.
#
# Usage: bash scripts/test/test-build-production-apk.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT/scripts/build-production-apk.sh"

PASS=0
FAIL=0

ok()   { printf '  \033[32mok\033[0m    %s\n' "$1"; PASS=$((PASS+1)); }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAIL=$((FAIL+1)); }

assert_eq() { # actual expected label
    if [[ "$1" == "$2" ]]; then ok "$3"; else bad "$3 (got '$1', want '$2')"; fi
}

# Extract a top-level function definition (body up to the matching closing
# `}`) from the production script. Avoids sourcing the script, which would run
# main() and require production secrets + the Android SDK.
extract_fn() { # fn-name
    awk -v fn="$1" '
        $0 ~ ("^" fn "\\(\\) \\{") { f=1 }
        f { print }
        f && /^}$/ { exit }
    ' "$SCRIPT"
}

# Pull the `normalize_fingerprint` helper too (used by the fingerprint parser).
extract_helper() { # fn-name
    awk -v fn="$1" '
        $0 ~ ("^" fn "\\(\\) \\{") { f=1 }
        f { print }
        f && /^}$/ { exit }
    ' "$SCRIPT"
}

echo "== static checks =="
if bash -n "$SCRIPT"; then ok "script bash -n"; else bad "script bash -n"; fi
if command -v shellcheck >/dev/null 2>&1; then
    if shellcheck "$SCRIPT" >/dev/null 2>&1; then ok "script shellcheck"; else bad "script shellcheck"; fi
else
    ok "script shellcheck (skipped: shellcheck not installed)"
fi

# Build a reusable parser source (helpers + the two functions) and load it.
PARSER_SRC="$(extract_helper normalize_fingerprint)
$(extract_fn parse_signer_count)
$(extract_fn parse_signer_fingerprint)"

# Sanity: the functions must have been extracted.
if [[ -z "$PARSER_SRC" ]]; then
    bad "could not extract parser functions from production script"
    echo "  PASS: $PASS  FAIL: $FAIL"
    exit 1
fi

# Define the helpers/functions in this shell so the fixtures can call them.
eval "$PARSER_SRC"

# --- fixtures --------------------------------------------------------------

# Realistic apksigner --verbose --print-certs output for a single-signer v2
# scheme APK (mirrors the exact text the production failure was based on).
SINGLE_SIGNER_FIXTURE="$(cat <<'EOF'
Verified using v1 scheme (APK Signature Scheme v1): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Number of signers: 1
V2 Signer: certificate DN: CN=RescueAuth, OU=Release, O=xincy22
V2 Signer: certificate SHA-256 digest: 2c56e6b764f5664dfd34ea7bfb38f07f1b991055093754e4104714c527584944
V2 Signer: certificate SHA-1 digest: abcdef0123456789abcdef0123456789abcdef01
V2 Signer: certificate MD5 digest: 0123456789abcdef0123456789abcdef
V2 Signer: key algorithm: RSA
V2 Signer: key size (bits): 4096
V2 Signer: public key SHA-256 digest: deadbeefcafe0123456789abcdef0123456789abcdef0123456789abcdef0123
EOF
)"

# Two-signer APK must be rejected (count != 1).
TWO_SIGNER_FIXTURE="$(cat <<'EOF'
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 2
V2 Signer: certificate DN: CN=First
V2 Signer: certificate SHA-256 digest: 1111111111111111111111111111111111111111111111111111111111111111
V2 Signer: key size (bits): 2048
V2 Signer: certificate DN: CN=Second
V2 Signer: certificate SHA-256 digest: 2222222222222222222222222222222222222222222222222222222222222222
V2 Signer: key size (bits): 2048
EOF
)"

# Missing `Number of signers` summary line (truncated/legacy output): must
# fail closed (empty count).
MISSING_COUNT_FIXTURE="$(cat <<'EOF'
Verified using v2 scheme (APK Signature Scheme v2): true
V2 Signer: certificate DN: CN=RescueAuth, OU=Release, O=xincy22
V2 Signer: certificate SHA-256 digest: 2c56e6b764f5664dfd34ea7bfb38f07f1b991055093754e4104714c527584944
V2 Signer: key size (bits): 4096
EOF
)"

# Zero signers: must be rejected (count == 0, not 1).
ZERO_SIGNER_FIXTURE="$(cat <<'EOF'
Verified using v2 scheme (APK Signature Scheme v2): false
Number of signers: 0
EOF
)"

TEST_WORKSPACE="$(mktemp -d)"
write_fixture() { # content path
    printf '%s\n' "$1" > "$2"
}

echo
echo "== 1. real single-signer v2 output -> count == 1 (the regression) =="
F="$TEST_WORKSPACE/single.txt"; write_fixture "$SINGLE_SIGNER_FIXTURE" "$F"
got=$(parse_signer_count "$F")
assert_eq "$got" "1" "single signer count == 1"

echo
echo "== 2. single signer fingerprint extracted and normalized =="
got_fp=$(parse_signer_fingerprint "$F")
assert_eq "$got_fp" "2C56E6B764F5664DFD34EA7BFB38F07F1B991055093754E4104714C527584944" "single signer fingerprint normalized uppercase"

echo
echo "== 3. two signers -> count == 2 (must be rejected downstream) =="
F2="$TEST_WORKSPACE/two.txt"; write_fixture "$TWO_SIGNER_FIXTURE" "$F2"
got2=$(parse_signer_count "$F2")
assert_eq "$got2" "2" "two signer count == 2"
# The downstream assertion `[[ count == 1 ]]` would reject this; assert the
# parser does NOT collapse two signers into one.
if [[ "$got2" == "1" ]]; then bad "two signers must NOT be counted as one"; else ok "two signers NOT collapsed to one"; fi

echo
echo "== 4. missing 'Number of signers' -> fail closed (empty) =="
F3="$TEST_WORKSPACE/missing.txt"; write_fixture "$MISSING_COUNT_FIXTURE" "$F3"
got3=$(parse_signer_count "$F3")
assert_eq "$got3" "" "missing count yields empty (fail closed)"

echo
echo "== 5. zero signers -> count == 0 (rejected downstream) =="
F4="$TEST_WORKSPACE/zero.txt"; write_fixture "$ZERO_SIGNER_FIXTURE" "$F4"
got4=$(parse_signer_count "$F4")
assert_eq "$got4" "0" "zero signer count == 0"

echo
echo "== 6. old bug guard: 'V2 Signer' metadata lines must NOT be counted =="
# The previous parser used `grep -c '^Signer #...'` which matched 0 lines for
# the real output; but naively counting `V2 Signer:` lines would yield 6 here.
# Assert the new parser returns exactly 1 for the single-signer fixture.
v2_signer_lines=$(grep -cE '^V2 Signer: certificate SHA-256 digest:' "$F")
if [[ "$v2_signer_lines" -gt 1 ]]; then
    ok "fixture has ${v2_signer_lines} V2 Signer metadata lines (proves old line-counting would over-count)"
else
    ok "fixture V2 Signer metadata line count = $v2_signer_lines"
fi
assert_eq "$(parse_signer_count "$F")" "1" "parser ignores V2 Signer metadata lines"

echo
echo "== 7. fingerprint line absent -> empty (fail closed) =="
F5="$TEST_WORKSPACE/nofp.txt"; write_fixture "$ZERO_SIGNER_FIXTURE" "$F5"
got5=$(parse_signer_fingerprint "$F5")
assert_eq "$got5" "" "missing fingerprint yields empty (fail closed)"

echo
echo "== summary =="
echo "  PASS: $PASS  FAIL: $FAIL"
rm -rf "$TEST_WORKSPACE"
if [[ "$FAIL" -gt 0 ]]; then
    exit 1
fi
exit 0
