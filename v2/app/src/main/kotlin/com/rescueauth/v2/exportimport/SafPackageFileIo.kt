package com.rescueauth.v2.exportimport

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.rescueauth.v2.export.BoundedPackageReader
import com.rescueauth.v2.export.PackageIdentifier
import com.rescueauth.v2.export.codec.PackageCodecException
import com.rescueauth.v2.export.codec.PackageFormat
import java.io.IOException

/**
 * Thin Android Storage Access Framework adapter (Issue #1 §5, §7).
 *
 * - **Export** writes only encrypted package bytes through a SAF
 *   `ContentResolver.openOutputStream` (ActivityResultContracts.CreateDocument).
 * - **Import** opens a SAF `ContentResolver.openInputStream`
 *   (ActivityResultContracts.OpenDocument) and funnels it through the pure,
 *   JVM-testable [BoundedPackageReader]. The provider's metadata (MIME,
 *   displayName, extension, `OpenableColumns.SIZE`) is **never trusted** —
 *   the actual package bytes + magic + codec validation are the only security
 *   gates (Issue #1 §7).
 *
 * This class deliberately has **no** `/sdcard` writes, no
 * `MANAGE_EXTERNAL_STORAGE`, no `READ/WRITE_EXTERNAL_STORAGE`, no fixed
 * Downloads path and no file browser (PACKAGE_FORMAT.md §SAF / Issue #1 §5).
 */
class SafPackageFileIo : PackageFileIo {

    override suspend fun readBounded(context: Context, uri: Uri): ByteArray {
        val resolver = context.contentResolver
        val stream = try {
            resolver.openInputStream(uri)
                ?: throw IOException("cannot open document stream")
        } catch (e: Exception) {
            throw ExportImportError.CannotOpenDocument
        }
        val bytes = stream.use { input ->
            BoundedPackageReader.readBounded(
                source = { buffer ->
                    try {
                        input.read(buffer)
                    } catch (e: Exception) {
                        throw BoundedPackageReader.ReadFailure("stream read failed", e)
                    }
                },
            )
        }
        return bytes
    }

    override suspend fun readPrefix(context: Context, uri: Uri, maxBytes: Int): ByteArray {
        val resolver = context.contentResolver
        val stream = try {
            resolver.openInputStream(uri)
                ?: throw IOException("cannot open document stream")
        } catch (e: Exception) {
            throw ExportImportError.CannotOpenDocument
        }
        val bytes = stream.use { input ->
            BoundedPackageReader.readPrefix(
                source = { buffer ->
                    try {
                        input.read(buffer)
                    } catch (e: Exception) {
                        throw BoundedPackageReader.ReadFailure("stream read failed", e)
                    }
                },
                maxBytes = maxBytes,
            )
        }
        return bytes
    }

    override suspend fun write(context: Context, uri: Uri, bytes: ByteArray) {
        val resolver = context.contentResolver
        val stream = try {
            resolver.openOutputStream(uri, "w")
                ?: throw IOException("cannot open output stream")
        } catch (e: Exception) {
            throw ExportImportError.WriteFailed
        }
        try {
            stream.use { out ->
                out.write(bytes)
                out.flush()
            }
        } catch (e: Exception) {
            // The write failed — propagate so the caller reports an explicit
            // export failure and never claims success. Best-effort cleanup is
            // attempted by the caller via the same SAF document if the provider
            // supports it.
            throw ExportImportError.WriteFailed
        }
    }

    override suspend fun deleteIfPossible(context: Context, uri: Uri) {
        // Best-effort: some providers cannot delete; never crash on that.
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
            // Provider does not support delete — ignore (Issue #1 §8).
        }
    }

    /** Display name for UX hints only — never a security gate (Issue #1 §7). */
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

    /** Size claim for UX hints only — never a security gate (Issue #1 §7). */
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
 * Package-file I/O seam so the coordinator / ViewModel can be unit-tested on
 * the JVM without a real ContentResolver (Issue #1 §24). The production
 * implementation is [SafPackageFileIo].
 */
interface PackageFileIo {
    suspend fun readBounded(context: Context, uri: Uri): ByteArray
    suspend fun readPrefix(context: Context, uri: Uri, maxBytes: Int = PackageFormat.MAGIC_BYTES): ByteArray
    suspend fun write(context: Context, uri: Uri, bytes: ByteArray)
    suspend fun deleteIfPossible(context: Context, uri: Uri)
    fun displayName(context: Context, uri: Uri): String?
    fun sizeHint(context: Context, uri: Uri): Long?
}

/**
 * Package extension / MIME strategy (PACKAGE_FORMAT.md §SAF contract, Phase 3D).
 *
 * The v2 native package uses a distinct extension and MIME so it can never be
 * confused visually or in code with legacy `.rakvault` files (Issue #1 §5).
 * The MIME is a hint for the SAF picker only; the actual file type is decided
 * by the package magic + codec validation, never by the MIME.
 */
object PackageFileContract {
    /** v2 native package file extension (distinct from legacy `.rakvault`). */
    const val PACKAGE_EXTENSION = "rakpkg"

    /** Suggested file name prefix for CreateDocument. */
    const val EXPORT_FILE_PREFIX = "rescueauth-package"

    /** Stable MIME hint for the v2 native package. */
    const val PACKAGE_MIME = "application/vnd.rescueauth.v2-package"

    /** Fallback MIME used by some providers / pickers. */
    const val PACKAGE_MIME_FALLBACK = "application/octet-stream"

    /** Accept any document on import — magic + bytes decide (Issue #1 §7). */
    val IMPORT_MIME_TYPES = arrayOf("*/*")

    /** Builds a suggested export file name with a timestamp. */
    fun suggestedExportFileName(nowEpochMillis: Long): String =
        "$EXPORT_FILE_PREFIX-${nowEpochMillis}.$PACKAGE_EXTENSION"
}

/**
 * Convenience for tests: in-memory fake that returns the same bytes on every
 * read and records writes / deletes.
 */
open class FakePackageFileIo(
    private val stored: ByteArray? = null,
    private val onWrite: ((ByteArray) -> Unit)? = null,
) : PackageFileIo {
    val written = mutableListOf<ByteArray>()
    val deletedUris = mutableListOf<Uri>()

    override suspend fun readBounded(context: Context, uri: Uri): ByteArray =
        stored ?: throw PackageCodecException.UnsupportedFormat("no stored bytes")

    override suspend fun readPrefix(context: Context, uri: Uri, maxBytes: Int): ByteArray {
        val s = stored ?: byteArrayOf()
        return s.take(minOf(maxBytes, s.size)).toByteArray()
    }

    override suspend fun write(context: Context, uri: Uri, bytes: ByteArray) {
        written += bytes.copyOf()
        onWrite?.invoke(bytes)
    }

    override suspend fun deleteIfPossible(context: Context, uri: Uri) {
        deletedUris += uri
    }

    override fun displayName(context: Context, uri: Uri): String? = "fake.${PackageFileContract.PACKAGE_EXTENSION}"

    override fun sizeHint(context: Context, uri: Uri): Long? = stored?.size?.toLong()
}
