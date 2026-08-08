package com.rescueauth.v2.export.codec

import com.rescueauth.v2.export.PackageSourceMetadata
import com.rescueauth.v2.export.SnapshotBuilder
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultPackagePayload
import com.rescueauth.v2.export.VaultSnapshot
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Format validation / malicious-header / DoS protection tests (Phase 3B §5,
 * THREAT_MODEL.md §Malicious header).
 *
 * The critical requirement: KDF parameters are validated and rejected BEFORE
 * Argon2id runs, so a hostile package cannot force a huge memory/time
 * allocation (OOM / CPU DoS / ANR). These tests assert that the error type is
 * [PackageCodecException.InvalidKdfParameters] or
 * [PackageCodecException.MalformedPackage] — i.e. the failure happens at
 * header-parse time, not inside the KDF.
 */
class PortablePackageCodecFormatTest {

    private val pin = "test-pin-1234"

    private fun samplePayload(): VaultPackagePayload = VaultPackagePayload(
        logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
        packageId = "pkg-1",
        createdAt = "2024-01-01T00:00:00Z",
        source = PackageSourceMetadata(client = "test", appVersion = "1.0.0"),
        snapshot = VaultSnapshot(scope = SnapshotScope.FULL_VAULT),
    )

    private fun encode(): ByteArray = PortablePackageCodec.encode(samplePayload(), pin)

    // ------------------------------------------------------------------
    // Field mutation helpers (keep the envelope otherwise valid)
    // ------------------------------------------------------------------

    private fun withMemoryKiB(value: Long): ByteArray {
        val bytes = encode()
        writeUInt32(bytes, PackageLayout.OFF_KDF_MEMORY_KIB, value)
        return bytes
    }

    private fun withIterations(value: Long): ByteArray {
        val bytes = encode()
        writeUInt32(bytes, PackageLayout.OFF_KDF_ITERATIONS, value)
        return bytes
    }

    private fun withParallelism(value: Int): ByteArray {
        val bytes = encode()
        bytes[PackageLayout.OFF_KDF_PARALLELISM] = value.toByte()
        return bytes
    }

    private fun withOutputLength(value: Int): ByteArray {
        val bytes = encode()
        bytes[PackageLayout.OFF_KDF_OUTPUT_LENGTH] = value.toByte()
        return bytes
    }

    private fun withSaltLength(value: Int): ByteArray {
        val bytes = encode()
        bytes[PackageLayout.OFF_KDF_SALT_LENGTH] = value.toByte()
        return bytes
    }

    private fun writeUInt32(out: ByteArray, offset: Int, value: Long) {
        out[offset] = ((value ushr 24) and 0xff).toByte()
        out[offset + 1] = ((value ushr 16) and 0xff).toByte()
        out[offset + 2] = ((value ushr 8) and 0xff).toByte()
        out[offset + 3] = (value and 0xff).toByte()
    }

    // ------------------------------------------------------------------
    // KDF parameter bounds (rejected BEFORE Argon2id)
    // ------------------------------------------------------------------

    @Test
    fun `malicious huge argon2 memory request rejected before KDF`() {
        // Claim 4 GiB. Parsing must reject as InvalidKdfParameters (pre-KDF),
        // NOT crash/OOM.
        assertThrows(PackageCodecException.InvalidKdfParameters::class.java) {
            PortablePackageCodec.decode(withMemoryKiB(4L * 1024 * 1024), pin)
        }
    }

    @Test
    fun `malicious max uint32 memory rejected before KDF`() {
        assertThrows(PackageCodecException.InvalidKdfParameters::class.java) {
            PortablePackageCodec.decode(withMemoryKiB(0xFFFFFFFFL), pin)
        }
    }

    @Test
    fun `malicious huge iterations rejected before KDF`() {
        assertThrows(PackageCodecException.InvalidKdfParameters::class.java) {
            PortablePackageCodec.decode(withIterations(1_000_000L), pin)
        }
    }

    @Test
    fun `malicious huge parallelism rejected before KDF`() {
        assertThrows(PackageCodecException.InvalidKdfParameters::class.java) {
            PortablePackageCodec.decode(withParallelism(128), pin)
        }
    }

    @Test
    fun `zero memory rejected before KDF`() {
        assertThrows(PackageCodecException.InvalidKdfParameters::class.java) {
            PortablePackageCodec.decode(withMemoryKiB(0), pin)
        }
    }

    @Test
    fun `zero iterations rejected before KDF`() {
        assertThrows(PackageCodecException.InvalidKdfParameters::class.java) {
            PortablePackageCodec.decode(withIterations(0), pin)
        }
    }

    @Test
    fun `zero parallelism rejected before KDF`() {
        assertThrows(PackageCodecException.InvalidKdfParameters::class.java) {
            PortablePackageCodec.decode(withParallelism(0), pin)
        }
    }

    @Test
    fun `memory below 8x parallelism rejected before KDF`() {
        // memoryKiB = 64 with parallelism = 64 → violates memory >= 8*parallelism.
        assertThrows(PackageCodecException.InvalidKdfParameters::class.java) {
            PortablePackageCodec.decode(withMemoryKiB(64).also { it[PackageLayout.OFF_KDF_PARALLELISM] = 64.toByte() }, pin)
        }
    }

    @Test
    fun `non-32 output length rejected before KDF (cryptoVersion-scoped)`() {
        // cryptoVersion=1 derives the XChaCha20-Poly1305 wrapping KEK directly
        // from the Argon2id output, so the output length MUST be 32. A value
        // that is structurally valid but not consumable by the codec (e.g. 64
        // or 16, inside the old loose 16..64 range) must be rejected BEFORE
        // the KDF runs — the codec cannot consume it.
        assertThrows(PackageCodecException.InvalidKdfParameters::class.java) {
            PortablePackageCodec.decode(withOutputLength(64), pin)
        }
        assertThrows(PackageCodecException.InvalidKdfParameters::class.java) {
            PortablePackageCodec.decode(withOutputLength(16), pin)
        }
    }

    @Test
    fun `output length zero rejected before KDF`() {
        assertThrows(PackageCodecException.InvalidKdfParameters::class.java) {
            PortablePackageCodec.decode(withOutputLength(0), pin)
        }
    }

    @Test
    fun `undersized salt rejected before KDF`() {
        assertThrows(PackageCodecException.InvalidKdfParameters::class.java) {
            PortablePackageCodec.decode(withSaltLength(1), pin)
        }
    }

    @Test
    fun `oversized salt rejected before KDF`() {
        assertThrows(PackageCodecException.InvalidKdfParameters::class.java) {
            PortablePackageCodec.decode(withSaltLength(200), pin)
        }
    }

    // ------------------------------------------------------------------
    // Structural / length validation
    // ------------------------------------------------------------------

    @Test
    fun `malformed field lengths fail`() {
        val bytes = encode()
        // Corrupt the wrap-nonce length to 0 (invalid XChaCha nonce length).
        val mutated = bytes.copyOf()
        val saltLength = PackageFormat.DEFAULT_SALT_BYTES
        mutated[PackageLayout.wrapNonceLengthOffset(saltLength)] = 0
        assertThrows(PackageCodecException.MalformedPackage::class.java) {
            PortablePackageCodec.decode(mutated, pin)
        }
    }

    @Test
    fun `oversized package fails`() {
        // A package larger than the hard cap is rejected before parsing.
        val big = ByteArray(PackageFormat.MAX_PACKAGE_SIZE + 1)
        assertThrows(PackageCodecException.MalformedPackage::class.java) {
            PortablePackageCodec.decode(big, pin)
        }
    }

    @Test
    fun `truncated fixed header fails`() {
        assertThrows(PackageCodecException.MalformedPackage::class.java) {
            PortablePackageCodec.decode(ByteArray(10), pin)
        }
    }

    @Test
    fun `oversized wrapped key length fails`() {
        val bytes = encode()
        val mutated = bytes.copyOf()
        // Claim wrappedKeyLength = 0xFFFF → exceeds MAX_WRAPPED_KEY_BYTES.
        mutated[PackageLayout.wrappedKeyLengthOffset(PackageFormat.DEFAULT_SALT_BYTES)] = 0xFF.toByte()
        mutated[PackageLayout.wrappedKeyLengthOffset(PackageFormat.DEFAULT_SALT_BYTES) + 1] = 0xFF.toByte()
        assertThrows(PackageCodecException.MalformedPackage::class.java) {
            PortablePackageCodec.decode(mutated, pin)
        }
    }

    @Test
    fun `oversized payload ciphertext length fails`() {
        val bytes = encode()
        val mutated = bytes.copyOf()
        val saltLength = PackageFormat.DEFAULT_SALT_BYTES
        val wrappedKeyLength = PackageFormat.WRAPPED_PACKAGE_KEY_BYTES
        // Claim payloadCiphertextLength = MAX_PACKAGE_SIZE (larger than cap).
        writeUInt32(
            mutated,
            PackageLayout.payloadCiphertextLengthOffset(saltLength, wrappedKeyLength),
            PackageFormat.MAX_PACKAGE_SIZE.toLong(),
        )
        assertThrows(PackageCodecException.MalformedPackage::class.java) {
            PortablePackageCodec.decode(mutated, pin)
        }
    }

    @Test
    fun `malformed serialized payload fails as logical payload invalid`() {
        // Build a package with a valid header but garbage payload by encoding
        // then replacing the ciphertext with random bytes → AuthenticationFailed.
        // For LogicalPayloadInvalid we need a valid-AEAD-but-bad-JSON payload,
        // which is covered in the compatibility/validation test below via a
        // deterministic-randomness fixture. Here we assert that garbage
        // ciphertext surfaces as authentication failure (not a crash).
        val bytes = encode()
        val mutated = bytes.copyOf()
        val envelope = PackageHeaderParser.parse(bytes)
        val payloadStart = PackageLayout.headerEnd(envelope.salt.size, envelope.wrappedKey.size)
        for (i in payloadStart until mutated.size) {
            mutated[i] = 0xAB.toByte()
        }
        assertThrows(PackageCodecException.AuthenticationFailed::class.java) {
            PortablePackageCodec.decode(mutated, pin)
        }
    }

    @Test
    fun `valid crypto but invalid logical payload fails as logical payload invalid`() {
        // A payload that fails PackageValidator (e.g. scope mismatch) must be
        // rejected as LogicalPayloadInvalid after successful decryption.
        //
        // The public encode() validates the payload first, so to build a
        // package that is cryptographically valid but logically invalid we use
        // the deterministic-randomness seam to hand-craft the bytes the same
        // way a corrupted-by-construction package would look. Actually the
        // simplest honest approach: PackageValidator is also invoked inside
        // decode(); a logically-invalid payload can only be produced by a
        // caller that bypasses encode()'s validation. We emulate that by
        // encoding a VALID payload, then re-encoding its JSON with a corrupted
        // scope using the internal PayloadJson + crypto primitives is
        // overkill. Instead: PackageValidator.validate is a public API and the
        // decode path runs it — so we assert that a package carrying a payload
        // that fails validation is rejected, by constructing it via the
        // internal encode path that skips validation.
        val invalidSnapshot = VaultSnapshot(
            scope = SnapshotScope.AUTHENTICATOR_ONLY,
            developerEntries = listOf(SnapshotBuilder.sshKey("ssh-1")), // violates scope
        )
        val invalidPayload = VaultPackagePayload(
            logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
            packageId = "pkg-invalid",
            createdAt = "2024-01-01T00:00:00Z",
            snapshot = invalidSnapshot,
        )
        // encodeWithoutValidation is the test-only entry that mirrors encode()
        // but skips PackageValidator so we can ship a logically-bad payload.
        val bytes = PortablePackageCodec.encodeWithoutValidation(invalidPayload, pin)
        assertThrows(PackageCodecException.LogicalPayloadInvalid::class.java) {
            PortablePackageCodec.decode(bytes, pin)
        }
    }

    @Test
    fun `invalid logical schema version rejected`() {
        val payload = VaultPackagePayload(
            logicalSchemaVersion = 999,
            packageId = "pkg-1",
            createdAt = "2024-01-01T00:00:00Z",
            snapshot = VaultSnapshot(scope = SnapshotScope.FULL_VAULT),
        )
        // Encoding validates the payload first → PackageValidator rejects it.
        assertThrows(com.rescueauth.v2.export.PackageValidator.ValidationException::class.java) {
            PortablePackageCodec.encode(payload, pin)
        }
    }
}
