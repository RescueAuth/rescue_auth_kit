package com.rescueauth.v2.legacy

import com.rescueauth.v2.export.PackageCapacity
import com.rescueauth.v2.export.PackageValidator
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultPackagePayload
import com.rescueauth.v2.export.VaultSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5A (CR fix) — Legacy defensive limits and logical-validator boundary
 * (Blocker 2, ADR-0010 §6).
 *
 * 1. Legacy defensive limits are chosen for legacy `.rakvault` (frozen v1 has
 *    NO size cap; real Developer Vaults can carry keystore binaries), and are
 *    NOT borrowed from the Native `.rakpkg` 16 MiB package contract.
 * 2. The Legacy path funnels through pure **logical** `VaultSnapshot`
 *    validation only — a logical-valid legacy snapshot must never be rejected
 *    by the Native package **capacity** policy (that budget belongs to the
 *    Native package codec/validator alone).
 */
class LegacyDefensiveLimitsAndValidatorBoundaryTest {

    // ------------------------------------------------------------------
    // 1. Legacy defensive limits are legacy-specific, not native 16 MiB
    // ------------------------------------------------------------------

    @Test
    fun `legacy input cap is 64 MiB not the native 16 MiB package cap`() {
        // The native .rakpkg hard limit is 16 MiB (PACKAGE_FORMAT.md).
        assertEquals(16 * 1024 * 1024, PackageCapacity.MAX_PACKAGE_SIZE)

        // Legacy is a DIFFERENT protocol: frozen v1.2.0 has no size cap, so the
        // legacy input cap is chosen independently (64 MiB — headroom for real
        // Developer Vaults with keystore binaries, still bounded for DoS).
        assertEquals(64 * 1024 * 1024, LegacyRakVaultImporter.DEFAULT_MAX_INPUT_BYTES)
        assertTrue(LegacyRakVaultImporter.DEFAULT_MAX_INPUT_BYTES > PackageCapacity.MAX_PACKAGE_SIZE)
    }

    @Test
    fun `oversized legacy input rejected at the legacy defensive limit`() {
        // A small importer instance with an explicit bound: oversize input is
        // rejected BEFORE any envelope/KDF work.
        val importer = LegacyRakVaultImporter(maxInputBytes = 100)
        val oversized = ByteArray(101) { 0x30 }
        val e = assertThrows(LegacyRakVaultImporter.ImportException::class.java) {
            importer.import(oversized, "pw")
        }
        assertTrue(e.message!!.contains("File too large"))

        // Malicious / oversized at the real default cap is rejected too.
        val atDefaultCap = ByteArray(LegacyRakVaultImporter.DEFAULT_MAX_INPUT_BYTES + 1)
        val e2 = assertThrows(LegacyRakVaultImporter.ImportException::class.java) {
            LegacyRakVaultImporter().import(atDefaultCap, "pw")
        }
        assertTrue(e2.message!!.contains("File too large"))
    }

    @Test
    fun `malicious legacy envelope rejected before Argon2`() {
        // Extreme KDF params (8 GiB claim) must be rejected by header
        // validation before any KDF work — the existing negative fixture.
        val bytes = javaClass.classLoader.getResourceAsStream("legacy-fixtures/extreme_kdf_params.rakvault")
            ?.use { it.readBytes() }
            ?: error("fixture missing")
        val e = assertThrows(LegacyRakVaultImporter.ImportException::class.java) {
            LegacyRakVaultImporter().import(bytes, "test-password-1")
        }
        assertTrue(e.message!!.contains("KDF memoryKiB") || e.message!!.contains("out of safe range"))
    }

    // ------------------------------------------------------------------
    // 2. Logical-validator dependency boundary
    // ------------------------------------------------------------------

    /**
     * A snapshot that is **logically valid** but whose estimated serialized
     * size exceeds the Native package capacity budget (two 8 MiB keystores —
     * each under the per-asset 12 MiB logical cap, but summing past the
     * 16 MiB package budget).
     */
    private fun logicalValidButNativeOversizedSnapshot(): VaultSnapshot {
        val big = "A".repeat(8 * 1024 * 1024) // 8 MiB base64, < 12 MiB per-asset cap
        return VaultSnapshot(
            scope = SnapshotScope.FULL_VAULT,
            developerEntries = listOf(
                com.rescueauth.v2.export.VaultAndroidSigningKey(
                    stableId = "legacy:developer:androidSigningKey:sk-a",
                    projectName = "a",
                    packageName = "a.b",
                    keystoreFileName = "a.jks",
                    keystoreBase64 = big,
                    storePassword = "p",
                    keyAlias = "a",
                    keyPassword = "p",
                    title = "a",
                    createdAt = "2024-01-01T00:00:00Z",
                    updatedAt = "2024-01-01T00:00:00Z",
                ),
                com.rescueauth.v2.export.VaultAndroidSigningKey(
                    stableId = "legacy:developer:androidSigningKey:sk-b",
                    projectName = "b",
                    packageName = "b.c",
                    keystoreFileName = "b.jks",
                    keystoreBase64 = big,
                    storePassword = "p",
                    keyAlias = "b",
                    keyPassword = "p",
                    title = "b",
                    createdAt = "2024-01-01T00:00:00Z",
                    updatedAt = "2024-01-01T00:00:00Z",
                ),
            ),
        )
    }

    @Test
    fun `logical valid legacy snapshot is accepted by logical validation`() {
        // The legacy adapter validates the mapped snapshot through the pure
        // logical overload (PackageValidator.validate(VaultSnapshot)). A
        // logical-valid snapshot is accepted EVEN IF it would exceed the
        // Native package capacity budget.
        val snapshot = logicalValidButNativeOversizedSnapshot()
        PackageValidator.validate(snapshot) // no exception
        PackageValidator.validateSnapshot(snapshot) // explicit logical entry point
    }

    @Test
    fun `native package capacity policy only rejects at the payload boundary`() {
        // The SAME logical snapshot, once wrapped into a Native package payload,
        // is rejected by the capacity budget — proving the capacity check is
        // owned by the Native package validator, not the shared logical layer.
        val snapshot = logicalValidButNativeOversizedSnapshot()
        val payload = VaultPackagePayload(
            logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
            packageId = "pkg-oversized",
            createdAt = "2024-01-01T00:00:00Z",
            snapshot = snapshot,
        )
        val e = assertThrows(PackageValidator.ValidationException::class.java) {
            PackageValidator.validate(payload)
        }
        assertTrue(e.message!!.contains("capacity budget"))
    }

    @Test
    fun `legacy adapter never routes through native package capacity`() {
        // Mapping a legacy bundle that is logically valid but would exceed the
        // Native package capacity must still produce a snapshot that passes the
        // shared LOGICAL validation used by the legacy path.
        //
        // (The large-keystore case above exercises the boundary through the
        // shared validator; this test asserts the mapper-level path itself never
        // imports / calls the Native codec capacity check — locked by the
        // source-level isolation test.)
        val legacyDir = java.io.File("src/main/kotlin/com/rescueauth/v2/legacy")
        var capacityRefs = 0
        legacyDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            if (file.readText().contains("PackageCapacity")) capacityRefs++
        }
        assertEquals("legacy adapter must not reference PackageCapacity", 0, capacityRefs)
    }

    @Test
    fun `legacy snapshot validation never runs the payload capacity budget`() {
        // Guard: the logical overload must NOT call validateCapacityBudget.
        // The oversized-but-logical-valid snapshot is the canary — if the
        // snapshot overload accidentally applied the capacity budget it would
        // throw here.
        PackageValidator.validate(logicalValidButNativeOversizedSnapshot())
        PackageValidator.validateSnapshot(logicalValidButNativeOversizedSnapshot())
    }
}
