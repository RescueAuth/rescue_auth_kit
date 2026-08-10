package com.rescueauth.v2.update

/**
 * Hard upper bounds for the fixed update manifest source (Issue #20 Phase 6
 * L2 / UPDATE_PROTOCOL.md §Networking bounds).
 *
 * The only two network reads are `latest.json` and `latest.json.sig`, both
 * served from the fixed public CNB raw source. These bounds are enforced
 * BEFORE any full-buffer allocation so an oversized / malicious remote payload
 * can never trigger an unbounded allocation (the "oversized manifest rejected
 * before unbounded allocation" contract).
 *
 * Values:
 * - manifest  64 KiB — a v1 schema manifest with a release-notes URL and
 *   description fields never approaches this; 64 KiB is generous yet small
 *   enough to cap memory trivially.
 * - signature  4 KiB — a Base64-encoded raw 64-byte Ed25519 signature is at
 *   most `ceil(64/3)*4 = 88` ASCII chars (+ optional trailing newline), so
 *   4 KiB is a very generous ceiling that still blocks any unreasonable blob.
 *
 * These are the chosen hard bounds (within the 64 KiB / 4 KiB recommendation);
 * see PHASE6_L2_REPORT.md for the rationale.
 */
object UpdateSizeLimits {
    const val MAX_MANIFEST_BYTES: Int = 64 * 1024
    const val MAX_SIGNATURE_BYTES: Int = 4 * 1024

    /** Raw length of an Ed25519 signature (RFC 8032). */
    const val ED25519_SIGNATURE_BYTES: Int = 64
}
