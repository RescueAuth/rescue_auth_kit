package com.rescueauth.v2.update

import java.util.Base64

/**
 * Ed25519 verification of the update manifest (UPDATE_PROTOCOL.md §签名与密钥).
 *
 * ## Trust boundary
 *
 * This is the **real trust boundary** for update data. The app fetches raw
 * bytes over HTTPS; this verifier checks the exact raw bytes of `latest.json`
 * against a Base64-encoded raw 64-byte Ed25519 signature using a trusted
 * public key. Only after verification succeeds may the caller parse the
 * manifest and trust its version / URL fields.
 *
 * ## Frozen byte contract
 *
 * - `latest.json.sig` = Base64-encoded raw 64-byte Ed25519 signature
 *   (ASCII, optional single trailing newline allowed).
 * - The signature covers the **EXACT raw bytes** of `latest.json` — NOT the
 *   parsed / pretty-printed / re-serialized / canonicalized JSON. This avoids
 *   any JSON canonicalization ambiguity (a release pipeline writes the exact
 *   bytes → signs the exact bytes → publishes the same bytes).
 *
 * ## Dependency reuse
 *
 * Reuses the already-present BouncyCastle dependency (`bcprov-jdk18on`, 1.85)
 * — no new crypto library is introduced. Ed25519 comes from BC's
 * RFC 8032 implementation (`org.bouncycastle.math.ec.rfc8032.Ed25519`).
 */
class UpdateManifestVerifier(
    /** The trusted Ed25519 public key, raw 32 bytes. */
    private val publicKey: ByteArray,
) {

    /** Outcome of a signature check. */
    sealed interface Result {
        /** Signature is valid over [manifestBytes]. */
        data class Valid(val manifestBytes: ByteArray) : Result
        /** Signature is invalid (wrong key / tampered / malformed). */
        data class Invalid(val reason: Reason) : Result

        enum class Reason {
            PUBLIC_KEY_NOT_CONFIGURED,
            PUBLIC_KEY_WRONG_LENGTH,
            SIGNATURE_TOO_LARGE,
            INVALID_BASE64,
            SIGNATURE_WRONG_LENGTH,
            VERIFY_FAILED,
        }
    }

    companion object {
        /** Raw length of an Ed25519 public key (RFC 8032). */
        const val PUBLIC_KEY_BYTES = 32

        private val decoder = Base64.getDecoder()

        /**
         * Decodes a Base64-encoded raw Ed25519 signature into its raw 64 bytes.
         *
         * - Trailing whitespace / a single trailing newline is trimmed.
         * - Decodes with strict (non-MIME) Base64 so malformed padding is
         *   rejected.
         *
         * @return the decoded raw signature, or null when not a valid 64-byte
         *   Ed25519 signature.
         */
        fun decodeSignature(signatureText: String): ByteArray? {
            if (signatureText.length > UpdateSizeLimits.MAX_SIGNATURE_BYTES) return null
            val trimmed = signatureText.trim()
            if (trimmed.isEmpty()) return null
            val raw = try {
                decoder.decode(trimmed)
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (raw.size != UpdateSizeLimits.ED25519_SIGNATURE_BYTES) return null
            return raw
        }
    }

    /**
     * Verifies [manifestBytes] against the Base64 [signatureText].
     *
     * Ordering: the exact [manifestBytes] passed here are the bytes the caller
     * fetched and will later parse. Verification happens BEFORE any parsing.
     *
     * @return [Result.Valid] only when the signature verifies over the exact
     *   raw [manifestBytes].
     */
    fun verify(manifestBytes: ByteArray, signatureText: String): Result {
        if (publicKey.isEmpty()) {
            return Result.Invalid(Result.Reason.PUBLIC_KEY_NOT_CONFIGURED)
        }
        if (publicKey.size != PUBLIC_KEY_BYTES) {
            return Result.Invalid(Result.Reason.PUBLIC_KEY_WRONG_LENGTH)
        }
        if (signatureText.length > UpdateSizeLimits.MAX_SIGNATURE_BYTES) {
            return Result.Invalid(Result.Reason.SIGNATURE_TOO_LARGE)
        }
        val signature = decodeSignature(signatureText)
            ?: return Result.Invalid(Result.Reason.INVALID_BASE64)

        val ok = try {
            org.bouncycastle.math.ec.rfc8032.Ed25519.verify(
                signature, 0,
                publicKey, 0,
                manifestBytes, 0, manifestBytes.size,
            )
        } catch (_: Exception) {
            false
        }
        return if (ok) {
            Result.Valid(manifestBytes)
        } else {
            Result.Invalid(Result.Reason.VERIFY_FAILED)
        }
    }
}
