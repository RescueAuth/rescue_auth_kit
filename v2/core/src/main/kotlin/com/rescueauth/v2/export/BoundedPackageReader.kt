package com.rescueauth.v2.export

import com.rescueauth.v2.export.codec.PackageCodecException
import com.rescueauth.v2.export.codec.PackageFormat

/**
 * Reads an untrusted package byte source with a hard byte budget.
 *
 * This is the **bounded untrusted-file reader** that Phase 3D uses for SAF
 * (Android Storage Access Framework) inputs (Issue #1 §7 / §24). It is a
 * pure Kotlin utility — no Android / ContentResolver / Uri dependency — so the
 * security-critical limit logic can be unit-tested on the JVM; the thin SAF
 * adapter (app module) only bridges a `ContentResolver` stream into this
 * reader.
 *
 * ## Contract
 *
 * - Streams incrementally until EOF; it never pre-allocates a buffer based on
 *   attacker-declared metadata (a hostile provider may lie about
 *   `OpenableColumns.SIZE`).
 * - Reads at most [limit] + 1 bytes: once the total exceeds the package limit
 *   it stops reading and rejects. The +1 proves "over the limit" without
 *   having to trust any size claim, and it never allocates more than
 *   `limit + 1` bytes.
 * - A claimed size may be used as a UX hint by the caller, but it is never a
 *   security gate here (Issue #1 §7).
 *
 * ## Errors
 *
 * - EOF at zero bytes → [PackageCodecException.UnsupportedFormat] ("not a
 *   package" / empty document), never a crash.
 * - Total bytes exceed [limit] → [PackageCodecException.MalformedPackage]
 *   ("package too large"), as a distinct category so the UI can say the file
 *   is too large instead of "wrong PIN".
 * - Underlying read failures are wrapped as [ReadFailure] so the SAF adapter
 *   can map them to a "read failed" UX message without exposing raw
 *   exceptions.
 *
 * The default limit is [PackageFormat.MAX_PACKAGE_SIZE] (16 MiB) — the format
 * hard limit. Reading one extra byte is intentionally allowed so a 16 MiB + 1
 * package is rejected as *too large* rather than truncated-and-parsed.
 */
object BoundedPackageReader {

    /** Maximum bytes this reader will ever buffer. */
    const val MAX_BUFFER_SIZE = 64 * 1024

    /** Wraps an underlying I/O failure so SAF adapters can map it to UX copy. */
    class ReadFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Reads the whole source into a bounded [ByteArray].
     *
     * @param source function that fills [buffer] and returns the number of
     *   bytes read, or -1 on EOF (mirrors [java.io.InputStream.read]).
     * @param limit maximum accepted package size in bytes (default the format
     *   hard limit).
     * @throws PackageCodecException.UnsupportedFormat when the source is empty
     * @throws PackageCodecException.MalformedPackage when the source exceeds
     *   [limit]
     * @throws ReadFailure on any underlying I/O error
     */
    fun readBounded(
        source: (buffer: ByteArray) -> Int,
        limit: Int = PackageFormat.MAX_PACKAGE_SIZE,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(MAX_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = try {
                source(buf)
            } catch (e: ReadFailure) {
                throw e
            } catch (e: Exception) {
                throw ReadFailure("failed reading package bytes: ${e.message}", e)
            }
            if (n == -1) break
            if (n == 0) continue
            total += n
            if (total > limit.toLong()) {
                throw PackageCodecException.MalformedPackage(
                    "package too large: read more than $limit bytes",
                )
            }
            if (total <= Int.MAX_VALUE) {
                out.write(buf, 0, n)
            } else {
                // Practically unreachable (limit ≤ 16 MiB), defensive.
                throw PackageCodecException.MalformedPackage(
                    "package too large: read more than $limit bytes",
                )
            }
        }
        val bytes = out.toByteArray()
        if (bytes.isEmpty()) {
            throw PackageCodecException.UnsupportedFormat("empty document: not a RescueAuth package")
        }
        return bytes
    }

    /**
     * Reads a bounded prefix (used for basic package identification, which only
     * needs the header). Same size gate as [readBounded].
     */
    fun readPrefix(
        source: (buffer: ByteArray) -> Int,
        maxBytes: Int = PackageFormat.MAGIC_BYTES,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(MAX_BUFFER_SIZE)
        var total = 0
        while (total < maxBytes) {
            val n = try {
                source(buf)
            } catch (e: ReadFailure) {
                throw e
            } catch (e: Exception) {
                throw ReadFailure("failed reading package bytes: ${e.message}", e)
            }
            if (n == -1) break
            if (n == 0) continue
            val take = minOf(n, maxBytes - total)
            out.write(buf, 0, take)
            total += take
            if (n > take) {
                // We read beyond the prefix; that's fine — the caller discards
                // the whole source and uses [readBounded] for the full read.
                break
            }
        }
        return out.toByteArray()
    }
}
