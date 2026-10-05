package com.rescueauth.v2.update

/**
 * The fixed update manifest source (UPDATE_PROTOCOL.md).
 *
 * - Organization / repo / release tag / path are part of the update trust contract:
 *   they must not be deleted, renamed or made private.
 * - The manifest source is always HTTPS and fixed at compile time — the user
 *   can never enter an update URL (Issue #20 §4).
 */
object UpdateManifestSource {

    const val MANIFEST_URL =
        "https://github.com/RescueAuth/rescue_auth_kit/releases/download/android-stable/latest.json"

    const val SIGNATURE_URL =
        "https://github.com/RescueAuth/rescue_auth_kit/releases/download/android-stable/latest.json.sig"
}
