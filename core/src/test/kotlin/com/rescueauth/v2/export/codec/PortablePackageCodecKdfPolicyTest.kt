package com.rescueauth.v2.export.codec

import com.rescueauth.v2.export.PackageSourceMetadata
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultPackagePayload
import com.rescueauth.v2.export.VaultSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * KDF policy tests (PACKAGE_FORMAT.md §KDF policy).
 *
 * Requirement: encode uses the current recommended [PackageFormat] defaults;
 * decode reads the parameters stored in each package (so future exports with
 * stronger KDF parameters still decrypt) but only accepts the safe decode
 * range — an attacker cannot force arbitrary unbounded memory/time.
 */
class PortablePackageCodecKdfPolicyTest {

    private val pin = "test-pin-1234"

    private fun samplePayload(): VaultPackagePayload = VaultPackagePayload(
        logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
        packageId = "pkg-1",
        createdAt = "2024-01-01T00:00:00Z",
        source = PackageSourceMetadata(client = "test", appVersion = "1.0.0"),
        snapshot = VaultSnapshot(scope = SnapshotScope.FULL_VAULT),
    )

    private fun writeUInt32(out: ByteArray, offset: Int, value: Long) {
        out[offset] = ((value ushr 24) and 0xff).toByte()
        out[offset + 1] = ((value ushr 16) and 0xff).toByte()
        out[offset + 2] = ((value ushr 8) and 0xff).toByte()
        out[offset + 3] = (value and 0xff).toByte()
    }

    @Test
    fun `encode writes the format defaults into the header`() {
        val bytes = PortablePackageCodec.encode(samplePayload(), pin)
        val env = PackageHeaderParser.parse(bytes)
        assertEquals(PackageFormat.DEFAULT_MEMORY_KIB, env.kdfMemoryKiB)
        assertEquals(PackageFormat.DEFAULT_ITERATIONS, env.kdfIterations)
        assertEquals(PackageFormat.DEFAULT_PARALLELISM, env.kdfParallelism)
        assertEquals(PackageFormat.DEFAULT_OUTPUT_LENGTH, env.kdfOutputLength)
        assertEquals(PackageFormat.DEFAULT_SALT_BYTES, env.salt.size)
    }

    @Test
    fun `decode reads package-stated kdf params instead of hardcoded defaults`() {
        // Encode with a stronger-but-accepted parameter set than the defaults
        // (32 MiB / 3 iterations). If decode hardcoded the defaults, the
        // wrapped key would not unwrap and this round-trip would fail.
        val strongerMemory = 32 * 1024 // 32 MiB
        val strongerIterations = 3
        val bytes = PortablePackageCodec.encodeWithKdfParams(
            samplePayload(), pin,
            memoryKiB = strongerMemory,
            iterations = strongerIterations,
            parallelism = 1,
            outputLength = 32,
        )
        val env = PackageHeaderParser.parse(bytes)
        assertEquals(strongerMemory, env.kdfMemoryKiB)
        assertEquals(strongerIterations, env.kdfIterations)

        val decoded = PortablePackageCodec.decode(bytes, pin)
        assertEquals(samplePayload(), decoded)
    }

    @Test
    fun `minimum accepted memory round-trips`() {
        val bytes = PortablePackageCodec.encodeWithKdfParams(
            samplePayload(), pin,
            memoryKiB = PackageFormat.MIN_MEMORY_KIB,
            iterations = 1,
            parallelism = 1,
            outputLength = 32,
        )
        assertEquals(samplePayload(), PortablePackageCodec.decode(bytes, pin))
    }

    // ------------------------------------------------------------------
    // Range-boundary rejection (cheap header mutation — the parser must
    // reject BEFORE Argon2id runs, so no expensive KDF is ever invoked).
    // ------------------------------------------------------------------

    @Test
    fun `memory just above accepted range rejected before kdf`() {
        val bytes = PortablePackageCodec.encode(samplePayload(), pin)
        writeUInt32(bytes, PackageLayout.OFF_KDF_MEMORY_KIB, (PackageFormat.MAX_MEMORY_KIB + 1).toLong())
        assertThrows(PackageCodecException.InvalidKdfParameters::class.java) {
            PortablePackageCodec.decode(bytes, pin)
        }
    }

    @Test
    fun `iterations just above accepted range rejected before kdf`() {
        val bytes = PortablePackageCodec.encode(samplePayload(), pin)
        writeUInt32(bytes, PackageLayout.OFF_KDF_ITERATIONS, (PackageFormat.MAX_ITERATIONS + 1).toLong())
        assertThrows(PackageCodecException.InvalidKdfParameters::class.java) {
            PortablePackageCodec.decode(bytes, pin)
        }
    }

    @Test
    fun `parallelism just above accepted range rejected before kdf`() {
        val bytes = PortablePackageCodec.encode(samplePayload(), pin)
        bytes[PackageLayout.OFF_KDF_PARALLELISM] = (PackageFormat.MAX_PARALLELISM + 1).toByte()
        assertThrows(PackageCodecException.InvalidKdfParameters::class.java) {
            PortablePackageCodec.decode(bytes, pin)
        }
    }

    @Test
    fun `memory just below accepted range rejected before kdf`() {
        val bytes = PortablePackageCodec.encode(samplePayload(), pin)
        writeUInt32(bytes, PackageLayout.OFF_KDF_MEMORY_KIB, (PackageFormat.MIN_MEMORY_KIB - 1).toLong())
        assertThrows(PackageCodecException.InvalidKdfParameters::class.java) {
            PortablePackageCodec.decode(bytes, pin)
        }
    }
}
