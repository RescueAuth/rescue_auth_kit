package com.rescueauth.v2.export.codec

import com.rescueauth.v2.export.PackageSourceMetadata
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultPackagePayload
import com.rescueauth.v2.export.VaultSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RUNTIME DECODE RESOURCE POLICY tests (PACKAGE_FORMAT.md §Runtime decode
 * resource policy / THREAT_MODEL.md).
 *
 * The key requirement: FORMAT HARD LIMIT (what the wire format can express)
 * is NOT the same as RUNTIME DECODE RESOURCE POLICY (what the Android decoder
 * will actually execute). A package whose parameters are structurally valid
 * and inside the format hard limits may still be rejected by the
 * production/default decoder BEFORE Argon2id runs if it exceeds the mobile
 * resource budget. The app-generated default (19 MiB / 2 iterations) must
 * remain fully compatible.
 */
class PortablePackageCodecRuntimePolicyTest {

    private val pin = "test-pin-1234"

    private fun samplePayload(): VaultPackagePayload = VaultPackagePayload(
        logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
        packageId = "pkg-runtime",
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

    // ------------------------------------------------------------------
    // Budget constants sanity
    // ------------------------------------------------------------------

    @Test
    fun `runtime budget is strictly tighter than the format hard limit`() {
        assertTrue(PackageRuntimePolicy.MAX_MEMORY_KIB < PackageFormat.MAX_MEMORY_KIB)
        assertTrue(PackageRuntimePolicy.MAX_ITERATIONS < PackageFormat.MAX_ITERATIONS)
        // Default encode parameters must always be inside the runtime budget.
        assertTrue(PackageFormat.DEFAULT_MEMORY_KIB <= PackageRuntimePolicy.MAX_MEMORY_KIB)
        assertTrue(PackageFormat.DEFAULT_ITERATIONS <= PackageRuntimePolicy.MAX_ITERATIONS)
        assertEquals(
            PackageFormat.RUNTIME_DEFAULT_COST_UNITS,
            PackageFormat.DEFAULT_MEMORY_KIB.toLong() * PackageFormat.DEFAULT_ITERATIONS,
        )
    }

    // ------------------------------------------------------------------
    // Default parameters stay compatible
    // ------------------------------------------------------------------

    @Test
    fun `default app parameters pass the runtime budget and round-trip`() {
        val bytes = PortablePackageCodec.encode(samplePayload(), pin)
        val env = PackageHeaderParser.parse(bytes)
        assertEquals(PackageFormat.DEFAULT_MEMORY_KIB, env.kdfMemoryKiB)
        assertEquals(PackageFormat.DEFAULT_ITERATIONS, env.kdfIterations)
        assertEquals(samplePayload(), PortablePackageCodec.decode(bytes, pin))
    }

    @Test
    fun `stronger but within-runtime params are accepted by the policy`() {
        // Pure policy check — no KDF executed. 64 MiB / 3 iterations is inside
        // the runtime budget (memory ≤ 128 MiB, iterations ≤ 8, cost 196 608 ≤
        // 512 K) and would decode fine; the default encode also passes.
        PackageRuntimePolicy.checkDecodeBudget(64 * 1024, 3, 1)
        PackageRuntimePolicy.checkDecodeBudget(
            PackageFormat.DEFAULT_MEMORY_KIB,
            PackageFormat.DEFAULT_ITERATIONS,
            PackageFormat.DEFAULT_PARALLELISM,
        )
    }

    // ------------------------------------------------------------------
    // Structurally-valid-but-over-budget → rejected BEFORE KDF
    // ------------------------------------------------------------------
    // All of the following parameter sets are inside the FORMAT HARD LIMIT
    // (256 MiB / 16 iterations) but above the RUNTIME budget. They must be
    // rejected as InvalidKdfParameters by the pure parameter check — never
    // executed.

    @Test
    fun `memory above runtime budget but below format limit rejected before KDF`() {
        // 200 MiB is < format max 256 MiB but > runtime max 128 MiB.
        val bytes = PortablePackageCodec.encode(samplePayload(), pin)
        writeUInt32(bytes, PackageLayout.OFF_KDF_MEMORY_KIB, (200L * 1024))
        assertThrows(PackageCodecException.InvalidKdfParameters::class.java) {
            PortablePackageCodec.decode(bytes, pin)
        }
    }

    @Test
    fun `iterations above runtime budget but below format limit rejected before KDF`() {
        // 12 iterations is < format max 16 but > runtime max 8.
        val bytes = PortablePackageCodec.encode(samplePayload(), pin)
        writeUInt32(bytes, PackageLayout.OFF_KDF_ITERATIONS, 12L)
        assertThrows(PackageCodecException.InvalidKdfParameters::class.java) {
            PortablePackageCodec.decode(bytes, pin)
        }
    }

    @Test
    fun `memory-iteration product above runtime budget rejected before KDF`() {
        // 100 MiB × 6 iterations: each axis is within runtime caps
        // (100 MiB ≤ 128 MiB, 6 ≤ 8) but the product 614 400 > 512 K budget.
        val bytes = PortablePackageCodec.encode(samplePayload(), pin)
        writeUInt32(bytes, PackageLayout.OFF_KDF_MEMORY_KIB, 100L * 1024)
        writeUInt32(bytes, PackageLayout.OFF_KDF_ITERATIONS, 6L)
        assertThrows(PackageCodecException.InvalidKdfParameters::class.java) {
            PortablePackageCodec.decode(bytes, pin)
        }
    }

    @Test
    fun `format-hard-limit params are rejected by runtime policy without executing KDF`() {
        // The absolute format extremes (256 MiB / 16 iterations) are inside the
        // format hard limit but 4× over the runtime budget — the decoder must
        // refuse them up-front. This is the exact "don't let format accepted
        // range auto-equate to executed cost" case.
        val bytes = PortablePackageCodec.encode(samplePayload(), pin)
        writeUInt32(bytes, PackageLayout.OFF_KDF_MEMORY_KIB, PackageFormat.MAX_MEMORY_KIB.toLong())
        writeUInt32(bytes, PackageLayout.OFF_KDF_ITERATIONS, PackageFormat.MAX_ITERATIONS.toLong())
        assertThrows(PackageCodecException.InvalidKdfParameters::class.java) {
            PortablePackageCodec.decode(bytes, pin)
        }
    }

    @Test
    fun `boundary at exactly the runtime budget is accepted`() {
        // Pure policy check — no KDF executed.
        // memory == max, cost == max (128 MiB × 4 = 524 288 units).
        PackageRuntimePolicy.checkDecodeBudget(
            PackageRuntimePolicy.MAX_MEMORY_KIB,
            4,
            1,
        )
    }

    @Test
    fun `one over the runtime budget boundary is rejected`() {
        // Pure policy check — no KDF executed.
        // 128 MiB × 5 = 655 360 units > 512 K budget.
        assertThrows(PackageCodecException.InvalidKdfParameters::class.java) {
            PackageRuntimePolicy.checkDecodeBudget(
                PackageRuntimePolicy.MAX_MEMORY_KIB,
                5,
                1,
            )
        }
    }
}
