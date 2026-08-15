package com.rescueauth.v2.export

import com.rescueauth.v2.export.codec.PackageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Basic package identification tests (Issue #1 §11).
 */
class PackageIdentifierTest {

    @Test
    fun `native v2 magic is identified`() {
        val result = PackageIdentifier.identify(PackageFormat.MAGIC.toByteArray(Charsets.US_ASCII))
        assertTrue(result is PackageIdentifier.Result.NativeV2Package)
    }

    @Test
    fun `short magic prefix with extra bytes is native`() {
        val bytes = (PackageFormat.MAGIC + "REST-OF-FILE").toByteArray(Charsets.US_ASCII)
        assertTrue(PackageIdentifier.identify(bytes) is PackageIdentifier.Result.NativeV2Package)
    }

    @Test
    fun `wrong magic is not native`() {
        val result = PackageIdentifier.identify("RAKVLEGACY".toByteArray(Charsets.US_ASCII))
        assertTrue(result is PackageIdentifier.Result.NotNativePackage)
        assertTrue((result as PackageIdentifier.Result.NotNativePackage).reason.isNotBlank())
    }

    @Test
    fun `legacy rakvault magic is not accepted by native path`() {
        // Legacy .rakvault files use a different envelope; the native import
        // must never accept them (ROADMAP §9 isolation).
        val result = PackageIdentifier.identify("RAKVAULT1".toByteArray(Charsets.US_ASCII))
        assertTrue(result is PackageIdentifier.Result.NotNativePackage)
    }

    @Test
    fun `empty document is reported as empty`() {
        assertEquals(
            PackageIdentifier.Result.EmptyDocument,
            PackageIdentifier.identify(ByteArray(0)),
        )
    }

    @Test
    fun `very short random bytes are not native`() {
        assertTrue(PackageIdentifier.identify(byteArrayOf(0x01, 0x02)) is PackageIdentifier.Result.NotNativePackage)
    }
}
