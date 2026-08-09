package com.rescueauth.v2.export

import com.rescueauth.v2.export.codec.PackageFormat

/**
 * Basic package identification for an untrusted document **before** any PIN /
 * crypto work.
 *
 * Phase 3D reads SAF inputs through a bounded reader and calls this before
 * asking for a PIN, so the UI can distinguish "not a RescueAuth package" from
 * "wrong PIN or corrupted package" and never runs Argon2id on a random file.
 *
 * ## What it inspects
 *
 * Only the first [PackageFormat.MAGIC_BYTES] bytes. The v2 native package
 * magic is `RAKVPKG2`; legacy `.rakvault` files use a different legacy
 * envelope and are deliberately NOT accepted by the Native Import path
 * (ROADMAP §9 — the two import paths stay fully isolated).
 *
 * A non-empty document whose magic does not match is reported as
 * [NotNativePackage], which the UI maps to "this does not look like a Rescue
 * Auth package" (and, for `.rakvault`, a hint that legacy import is a separate
 * flow, Phase 5). A zero-byte document is [EmptyDocument].
 */
object PackageIdentifier {

    sealed interface Result {
        /** Native v2 package (magic matched) — safe to request the PIN. */
        object NativeV2Package : Result

        /** Not a native package (wrong magic / other format). */
        data class NotNativePackage(val reason: String) : Result

        /** Empty / zero-byte document. */
        object EmptyDocument : Result
    }

    /**
     * @param prefix the first [PackageFormat.MAGIC_BYTES] bytes of the
     *   document (or fewer if the document is shorter).
     */
    fun identify(prefix: ByteArray): Result {
        if (prefix.isEmpty()) return Result.EmptyDocument
        if (prefix.size >= PackageFormat.MAGIC_BYTES &&
            String(prefix, 0, PackageFormat.MAGIC_BYTES, Charsets.US_ASCII) == PackageFormat.MAGIC
        ) {
            return Result.NativeV2Package
        }
        return Result.NotNativePackage("document is not a v2 RescueAuth package (bad magic)")
    }
}
