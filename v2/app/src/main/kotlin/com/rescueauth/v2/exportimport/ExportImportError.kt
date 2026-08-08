package com.rescueauth.v2.exportimport

import com.rescueauth.v2.export.codec.PackageCodecException

/**
 * Export / Import error taxonomy → Android UX mapping (Issue #1 §19).
 *
 * The UI maps every failure to a safe, actionable message; it never shows a
 * stack trace, a raw Uri, a secret, ciphertext bytes, or KDF internals.
 * Technical detail (without credentials) may go into debug-safe structured
 * diagnostics.
 */
sealed class ExportImportError : Exception() {

    /** The user cancelled the operation (SAF picker / PIN dialog). */
    data object UserCancelled : ExportImportError()

    /** The SAF document could not be opened (missing / revoked / IO error). */
    data object CannotOpenDocument : ExportImportError()

    /** Reading the document stream failed mid-read. */
    data object ReadFailed : ExportImportError()

    /** The document exceeds the package size limit (16 MiB). */
    data object PackageTooLarge : ExportImportError()

    /** Writing the SAF document failed. */
    data object WriteFailed : ExportImportError()

    /** Not a v2 RescueAuth package (bad magic / empty / other format). */
    data class UnsupportedFormat(val reason: String) : ExportImportError()

    /** Unsupported cryptoVersion / algorithm id. */
    data object UnsupportedCrypto : ExportImportError()

    /** Structurally broken header / package (truncation, lengths, garbage). */
    data object MalformedPackage : ExportImportError()

    /** KDF parameters outside the accepted range / runtime budget (pre-KDF). */
    data object InvalidKdfParameters : ExportImportError()

    /** Wrong PIN or corrupted package (deliberately not distinguished). */
    data object AuthenticationFailed : ExportImportError()

    /** Crypto succeeded but the logical payload is invalid. */
    data object LogicalPayloadInvalid : ExportImportError()

    /** The Vault session is locked / changed — the operation must restart. */
    data object SessionLocked : ExportImportError()

    /** The export snapshot could not be built consistently. */
    data object ExportSnapshotFailed : ExportImportError()

    /** The operation failed for an unknown reason (safe generic message). */
    data object Unknown : ExportImportError()

    /** Human-readable (non-secret) detail for diagnostics. */
    override val message: String
        get() = when (this) {
            UserCancelled -> "user cancelled"
            CannotOpenDocument -> "cannot open document"
            ReadFailed -> "read failed"
            PackageTooLarge -> "package too large"
            WriteFailed -> "write failed"
            is UnsupportedFormat -> reason
            UnsupportedCrypto -> "unsupported crypto"
            MalformedPackage -> "malformed package"
            InvalidKdfParameters -> "invalid KDF parameters"
            AuthenticationFailed -> "authentication failed"
            LogicalPayloadInvalid -> "logical payload invalid"
            SessionLocked -> "session locked"
            ExportSnapshotFailed -> "export snapshot failed"
            Unknown -> "unknown error"
        }

    companion object {
        /**
         * Maps a codec exception from [PortablePackageCodec] to the UI-safe
         * taxonomy. All codec failure categories are coarse by design; the
         * UI text for [AuthenticationFailed] is "wrong PIN or corrupted
         * package" (never a precise crypto reason).
         */
        fun fromCodec(e: PackageCodecException): ExportImportError = when (e) {
            is PackageCodecException.UnsupportedFormat -> UnsupportedFormat(e.message ?: "unsupported format")
            is PackageCodecException.UnsupportedCrypto -> UnsupportedCrypto
            is PackageCodecException.InvalidKdfParameters -> InvalidKdfParameters
            is PackageCodecException.MalformedPackage -> MalformedPackage
            is PackageCodecException.AuthenticationFailed -> AuthenticationFailed
            is PackageCodecException.LogicalPayloadInvalid -> LogicalPayloadInvalid
            is PackageCodecException.PackageTooLarge -> PackageTooLarge
        }

        /** Maps a bounded-reader failure to the taxonomy. */
        fun fromRead(e: Exception): ExportImportError = when (e) {
            is PackageCodecException.UnsupportedFormat -> UnsupportedFormat(e.message ?: "not a package")
            is PackageCodecException.MalformedPackage -> MalformedPackage
            is com.rescueauth.v2.export.BoundedPackageReader.ReadFailure -> ReadFailed
            else -> Unknown
        }
    }
}
