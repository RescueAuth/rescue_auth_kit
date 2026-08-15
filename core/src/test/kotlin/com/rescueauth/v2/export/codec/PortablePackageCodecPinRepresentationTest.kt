package com.rescueauth.v2.export.codec

import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultPackagePayload
import com.rescueauth.v2.export.VaultSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * PIN representation / zeroization overloads (PACKAGE_FORMAT.md §PIN
 * representation). The codec accepts String / CharArray / ByteArray PINs;
 * internally it always works on an owned ByteArray that is best-effort
 * zeroized after use.
 */
class PortablePackageCodecPinRepresentationTest {

    private fun payload() = VaultPackagePayload(
        logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
        packageId = "pkg-1",
        createdAt = "2024-01-01T00:00:00Z",
        snapshot = VaultSnapshot(scope = SnapshotScope.FULL_VAULT),
    )

    @Test
    fun `chararray pin encodes and decodes`() {
        val pin = charArrayOf('s', 'e', 'c', 'r', 'e', 't', '1', '2', '3')
        val bytes = PortablePackageCodec.encode(payload(), pin)
        assertEquals(payload(), PortablePackageCodec.decode(bytes, pin))
    }

    @Test
    fun `bytearray pin encodes and decodes`() {
        val pin = "secret123".toByteArray(Charsets.UTF_8)
        val bytes = PortablePackageCodec.encode(payload(), pin)
        assertEquals(payload(), PortablePackageCodec.decode(bytes, pin))
    }

    @Test
    fun `string pin and chararray pin with same content are interchangeable`() {
        val pinString = "secret123"
        val pinChars = pinString.toCharArray()
        val bytes = PortablePackageCodec.encode(payload(), pinString)
        assertEquals(payload(), PortablePackageCodec.decode(bytes, pinChars))
    }

    @Test
    fun `wrong chararray pin fails`() {
        val bytes = PortablePackageCodec.encode(payload(), "secret123")
        assertThrows(PackageCodecException.AuthenticationFailed::class.java) {
            PortablePackageCodec.decode(bytes, "wrong123".toCharArray())
        }
    }
}
