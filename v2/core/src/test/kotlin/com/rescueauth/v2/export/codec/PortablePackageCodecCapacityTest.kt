package com.rescueauth.v2.export.codec

import com.rescueauth.v2.export.PackageCapacity
import com.rescueauth.v2.export.PackageSourceMetadata
import com.rescueauth.v2.export.PackageValidator
import com.rescueauth.v2.export.SnapshotBuilder
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultPackagePayload
import com.rescueauth.v2.export.VaultSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Package capacity boundary tests (PACKAGE_FORMAT.md §Capacity).
 *
 * - A logical-valid payload is guaranteed encodable (the validator enforces
 *   the same budget as the codec).
 * - An over-limit encode fails EXPLICITLY with [PackageCodecException.PackageTooLarge]
 *   before any oversized allocation — never OOM.
 * - The codec-level hard limits are enforced.
 */
class PortablePackageCodecCapacityTest {

    private val pin = "test-pin-1234"

    private fun payload(snapshot: VaultSnapshot) = VaultPackagePayload(
        logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
        packageId = "pkg-capacity",
        createdAt = "2024-01-01T00:00:00Z",
        source = PackageSourceMetadata(client = "test", appVersion = "1.0.0"),
        snapshot = snapshot,
    )

    @Test
    fun `package format caps are shared with the logical validator`() {
        // Single source of truth: the codec's package limits must equal the
        // logical validator's budget chain. Otherwise a validator-accepted
        // payload could be impossible to export.
        assertEquals(PackageCapacity.MAX_PACKAGE_SIZE, PackageFormat.MAX_PACKAGE_SIZE)
        assertEquals(PackageCapacity.MAX_HEADER_LENGTH, PackageFormat.MAX_HEADER_LENGTH)
        assertEquals(PackageCapacity.MAX_PAYLOAD_CIPHERTEXT_SIZE, PackageFormat.MAX_PAYLOAD_CIPHERTEXT_SIZE)
        assertEquals(PackageCapacity.MAX_SERIALIZED_PAYLOAD_SIZE, PackageFormat.MAX_SERIALIZED_PAYLOAD_SIZE)
        assertEquals(PackageCapacity.MAX_SERIALIZED_PAYLOAD_SIZE, PackageValidator.MAX_SERIALIZED_PAYLOAD_BUDGET)
        assertEquals(PackageCapacity.MAX_WRAPPED_KEY_BYTES, PackageFormat.MAX_WRAPPED_KEY_BYTES)
    }

    @Test
    fun `validator-accepted near-limit payload encodes and round-trips`() {
        // A payload close to the budget (large keystore, still under the cap)
        // must encode and decode cleanly.
        val keystore = "A".repeat(11 * 1024 * 1024) // ~11 MiB base64, under the 12 MiB per-asset cap
        val snapshot = VaultSnapshot(
            scope = SnapshotScope.DEVELOPER_ONLY,
            developerEntries = listOf(
                SnapshotBuilder.signingKey("sk-1", keystoreBase64 = keystore),
            ),
        )
        val p = payload(snapshot)
        PackageValidator.validate(p) // accepted
        val bytes = PortablePackageCodec.encode(p, pin)
        val decoded = PortablePackageCodec.decode(bytes, pin)
        assertEquals(p, decoded)
    }

    @Test
    fun `over-budget payload fails validation before encode`() {
        // Two 8 MiB keystores: individually under the per-asset cap, combined
        // over the total budget. The logical validator must reject it so the
        // "validator accepts but cannot export" state is impossible.
        val keystore = "A".repeat(8 * 1024 * 1024)
        val p = payload(
            VaultSnapshot(
                scope = SnapshotScope.DEVELOPER_ONLY,
                developerEntries = listOf(
                    SnapshotBuilder.signingKey("sk-1", keystoreBase64 = keystore),
                    SnapshotBuilder.signingKey("sk-2", keystoreBase64 = keystore),
                ),
            ),
        )
        val ex = assertThrows(PackageValidator.ValidationException::class.java) {
            PortablePackageCodec.encode(p, pin)
        }
        assertTrue(ex.message!!.contains("capacity budget"))
    }

    @Test
    fun `encode of an over-limit payload fails explicitly without OOM`() {
        // Defense-in-depth: bypass the validator via the test-only entry and
        // make the codec's own exact post-serialization guard fire. It must
        // throw PackageTooLarge, not OOM. The keystore is ~18 MiB of base64
        // text, so the serialized payload exceeds the 16 MiB package budget.
        val keystore = "A".repeat(18 * 1024 * 1024) // ~18 MiB base64
        val p = payload(
            VaultSnapshot(
                scope = SnapshotScope.DEVELOPER_ONLY,
                developerEntries = listOf(
                    SnapshotBuilder.signingKey("sk-1", keystoreBase64 = keystore),
                ),
            ),
        )
        val ex = assertThrows(PackageCodecException.PackageTooLarge::class.java) {
            PortablePackageCodec.encodeWithoutValidation(p, pin)
        }
        assertTrue(ex.message!!.contains("exceeding the package budget"))
    }

    @Test
    fun `payload ciphertext larger than capacity rejected as malformed`() {
        val bytes = PortablePackageCodec.encode(payload(VaultSnapshot(scope = SnapshotScope.FULL_VAULT)), pin)
        val mutated = bytes.copyOf()
        val saltLength = PackageFormat.DEFAULT_SALT_BYTES
        val wrappedKeyLength = PackageFormat.WRAPPED_PACKAGE_KEY_BYTES
        // Claim a ciphertext length above the payload cap but below the old
        // whole-package cap → must be rejected.
        val tooLarge = PackageCapacity.MAX_PAYLOAD_CIPHERTEXT_SIZE + 1L
        writeUInt32(
            mutated,
            PackageLayout.payloadCiphertextLengthOffset(saltLength, wrappedKeyLength),
            tooLarge,
        )
        assertThrows(PackageCodecException.MalformedPackage::class.java) {
            PortablePackageCodec.decode(mutated, pin)
        }
    }

    @Test
    fun `package over the hard cap rejected before parsing`() {
        val big = ByteArray(PackageFormat.MAX_PACKAGE_SIZE + 1)
        assertThrows(PackageCodecException.MalformedPackage::class.java) {
            PortablePackageCodec.decode(big, pin)
        }
    }

    private fun writeUInt32(out: ByteArray, offset: Int, value: Long) {
        out[offset] = ((value ushr 24) and 0xff).toByte()
        out[offset + 1] = ((value ushr 16) and 0xff).toByte()
        out[offset + 2] = ((value ushr 8) and 0xff).toByte()
        out[offset + 3] = (value and 0xff).toByte()
    }
}
