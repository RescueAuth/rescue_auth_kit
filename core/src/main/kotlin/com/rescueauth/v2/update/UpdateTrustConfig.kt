package com.rescueauth.v2.update

import java.util.Base64

/**
 * Trust configuration boundary for the update protocol.
 *
 * The Ed25519 **public** key is not secret; the **private** key is release
 * infrastructure secret. This class defines where the public key comes from so
 * a production key can be injected at release provisioning time without the
 * source tree containing a fake "production" key (Issue #20 §8).
 *
 * ## Encoding
 *
 * The public key uses a single, documented encoding that matches the existing
 * BouncyCastle / JCA implementation: **Base64-encoded raw 32-byte Ed25519
 * public key** (the same raw bytes `Ed25519.verify` consumes). This is a
 * stable, implementation-matching encoding (see UPDATE_PROTOCOL.md §Public
 * key encoding).
 *
 * ## Not configured
 *
 * When [encodedPublicKey] is null/blank, the update check returns
 * `NOT_CONFIGURED` ("Verification key not configured / Update verification
 * unavailable") and ONLY the update check fails — the Vault keeps working.
 */
class UpdateTrustConfig(
    /** Base64-encoded raw 32-byte Ed25519 public key, or null when unprovisioned. */
    val encodedPublicKey: String?,
) {

    /** Raw 32-byte public key, or null when not configured. */
    fun rawPublicKey(): ByteArray? {
        val encoded = encodedPublicKey?.trim().orEmpty()
        if (encoded.isEmpty()) return null
        return try {
            val raw = Base64.getDecoder().decode(encoded)
            if (raw.size != UpdateManifestVerifier.PUBLIC_KEY_BYTES) null else raw
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        /** The not-configured instance — update verification unavailable. */
        fun notConfigured(): UpdateTrustConfig = UpdateTrustConfig(null)
    }
}
