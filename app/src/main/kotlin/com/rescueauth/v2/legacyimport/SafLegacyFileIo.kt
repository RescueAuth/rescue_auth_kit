package com.rescueauth.v2.legacyimport

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.rescueauth.v2.export.BoundedPackageReader
import com.rescueauth.v2.export.codec.PackageCodecException
import com.rescueauth.v2.legacy.LegacyRakVaultImporter

/**
 * Legacy `.rakvault` SAF file I/O (Phase 5B).
 *
 * ## Bounded read — Legacy 64 MiB policy (NOT the Native 16 MiB)
 *
 * The Legacy `.rakvault` protocol has NO 16 MiB cap (frozen v1 reads the whole
 * file via `readAsBytes`); Phase 5A chose an independent **defensive** input
 * cap of 64 MiB (headroom for real Developer Vaults with keystore binaries,
 * still bounded for DoS). This adapter therefore reads with
 * [LegacyRakVaultImporter.DEFAULT_MAX_INPUT_BYTES] **+ 1 detection**: once the
 * total exceeds 64 MiB it stops reading and rejects — the "over the limit"
 * is proven by actually reading the extra byte, never by trusting
 * `OpenableColumns.SIZE` (Issue #1 §5, §7).
 *
 * ## MIME / filename are UX hints only
 *
 * `.rakvault` MIME on Android is unreliable and is NEVER a security gate. The
 * actual authenticity verification is done by the legacy importer / envelope
 * (magic + KDF header + AEAD). The filename/extension may be used as a UX hint
 * only (Issue #1 §5).
 *
 * This class has no `/sdcard` writes, no external-storage permission, no fixed
 * Downloads path and no file browser.
 */
class SafLegacyFileIo : LegacyFileIo {

    override suspend fun readBounded(context: Context, uri: Uri): ByteArray {
        val resolver = context.contentResolver
        val stream = try {
            resolver.openInputStream(uri)
                ?: throw LegacyFileError.CannotOpenDocument
        } catch (e: LegacyFileError) {
            throw e
        } catch (e: Exception) {
            throw LegacyFileError.CannotOpenDocument
        }
        return stream.use { input ->
            try {
                BoundedPackageReader.readBounded(
                    source = { buffer ->
                        try {
                            input.read(buffer)
                        } catch (e: Exception) {
                            throw BoundedPackageReader.ReadFailure("legacy stream read failed", e)
                        }
                    },
                    limit = LegacyRakVaultImporter.DEFAULT_MAX_INPUT_BYTES,
                )
            } catch (e: PackageCodecException.MalformedPackage) {
                // > 64 MiB + 1 actually read → reject BEFORE any decrypt.
                throw LegacyFileError.FileTooLarge
            } catch (e: PackageCodecException.UnsupportedFormat) {
                // Zero-byte document.
                throw LegacyFileError.FileEmpty
            } catch (e: BoundedPackageReader.ReadFailure) {
                throw LegacyFileError.ReadFailed
            }
        }
    }

    override fun displayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun sizeHint(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Coarse legacy-file read errors (Phase 5B). Distinct from the Native
 * export/import error taxonomy — Legacy and Native error enums are
 * deliberately separate (ROADMAP §9).
 */
sealed class LegacyFileError : Exception() {
    /** User cancelled the SAF picker. */
    data object UserCancelled : LegacyFileError()

    /** The document could not be opened (missing / revoked / IO error). */
    data object CannotOpenDocument : LegacyFileError()

    /** Reading the document stream failed mid-read. */
    data object ReadFailed : LegacyFileError()

    /** Zero-byte document. */
    data object FileEmpty : LegacyFileError()

    /** The document exceeds the Legacy 64 MiB defensive cap (before decrypt). */
    data object FileTooLarge : LegacyFileError()
}

/**
 * Legacy-file I/O seam so the ViewModel is JVM-testable without a real
 * ContentResolver. Production implementation is [SafLegacyFileIo].
 */
interface LegacyFileIo {
    suspend fun readBounded(context: Context, uri: Uri): ByteArray
    fun displayName(context: Context, uri: Uri): String?
    fun sizeHint(context: Context, uri: Uri): Long?
}

/** Convenience for tests: returns the same bytes on every read. */
open class FakeLegacyFileIo(
    private val stored: ByteArray? = null,
) : LegacyFileIo {
    override suspend fun readBounded(context: Context, uri: Uri): ByteArray {
        val s = stored ?: throw LegacyFileError.CannotOpenDocument
        // Mirrors production SafLegacyFileIo: enforce the 64 MiB + 1 bound
        // BEFORE any decrypt (Issue #1 §5).
        if (s.size > LegacyRakVaultImporter.DEFAULT_MAX_INPUT_BYTES) {
            throw LegacyFileError.FileTooLarge
        }
        return s
    }

    override fun displayName(context: Context, uri: Uri): String? =
        "fake.rakvault"

    override fun sizeHint(context: Context, uri: Uri): Long? =
        stored?.size?.toLong()
}
