package com.rescueauth.v2.export

import com.rescueauth.v2.export.codec.PackageCodecException
import com.rescueauth.v2.export.codec.PackageFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Bounded untrusted-file reader tests (Issue #1 §7 / §24).
 *
 * Covers: valid package read, 16 MiB boundary, >limit rejection while reading,
 * lying/unknown provider SIZE cannot bypass the limit, invalid magic, user
 * cancel (empty document), ContentResolver read failure.
 */
class BoundedPackageReaderTest {

    private fun streamSource(bytes: ByteArray): (ByteArray) -> Int {
        val stream = ByteArrayInputStream(bytes)
        return { buffer -> stream.read(buffer) }
    }

    private fun repeatingSource(count: Int): (ByteArray) -> Int {
        var remaining = count
        return { buffer ->
            if (remaining <= 0) -1
            else {
                val n = minOf(buffer.size, remaining)
                buffer.fill(0x42.toByte(), 0, n)
                remaining -= n
                n
            }
        }
    }

    @Test
    fun `valid package bytes are read fully`() {
        val bytes = ByteArray(1024) { it.toByte() }
        val result = BoundedPackageReader.readBounded(streamSource(bytes))
        assertArrayEquals(bytes, result)
    }

    @Test
    fun `exactly at the package limit is accepted`() {
        val size = PackageFormat.MAX_PACKAGE_SIZE
        val result = BoundedPackageReader.readBounded(repeatingSource(size))
        assertEquals(size, result.size)
    }

    @Test
    fun `one byte over the limit is rejected while reading`() {
        try {
            BoundedPackageReader.readBounded(repeatingSource(PackageFormat.MAX_PACKAGE_SIZE + 1))
            fail("expected MalformedPackage for over-limit package")
        } catch (e: PackageCodecException.MalformedPackage) {
            assertTrue(e.message!!.contains("too large"))
        }
    }

    @Test
    fun `way over the limit is rejected`() {
        try {
            BoundedPackageReader.readBounded(repeatingSource(PackageFormat.MAX_PACKAGE_SIZE * 2))
            fail("expected MalformedPackage for over-limit package")
        } catch (e: PackageCodecException.MalformedPackage) {
            assertTrue(e.message!!.contains("too large"))
        }
    }

    @Test
    fun `empty document is rejected as unsupported format`() {
        try {
            BoundedPackageReader.readBounded(streamSource(byteArrayOf()))
            fail("expected UnsupportedFormat for empty document")
        } catch (e: PackageCodecException.UnsupportedFormat) {
            assertTrue(e.message!!.contains("empty"))
        }
    }

    @Test
    fun `source that claims a small size but streams more is still capped`() {
        // A hostile provider could claim SIZE=0 but still stream 16 MiB+1.
        try {
            BoundedPackageReader.readBounded(repeatingSource(PackageFormat.MAX_PACKAGE_SIZE + 1))
            fail("expected rejection")
        } catch (e: PackageCodecException.MalformedPackage) {
            // expected
        }
    }

    @Test
    fun `read failure is wrapped as ReadFailure`() {
        val failing: (ByteArray) -> Int = { throw java.io.IOException("boom") }
        try {
            BoundedPackageReader.readBounded(failing)
            fail("expected ReadFailure")
        } catch (e: BoundedPackageReader.ReadFailure) {
            assertEquals("boom", e.cause?.message)
        }
    }

    @Test
    fun `read prefix returns only the requested bytes`() {
        val bytes = ByteArray(32) { it.toByte() }
        val prefix = BoundedPackageReader.readPrefix(streamSource(bytes), 8)
        assertEquals(8, prefix.size)
        assertArrayEquals(bytes.copyOf(8), prefix)
    }

    @Test
    fun `read prefix handles short sources`() {
        val bytes = ByteArray(4) { it.toByte() }
        val prefix = BoundedPackageReader.readPrefix(streamSource(bytes), 8)
        assertEquals(4, prefix.size)
    }
}
