package com.rescueauth.v2.legacy

import com.rescueauth.v2.export.MergePlanner
import com.rescueauth.v2.export.PackageValidator
import com.rescueauth.v2.export.VaultSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5A §17 — idempotence through the shared MergePlanner.
 *
 * Mapping the same legacy fixture twice yields identical stableIds, so the
 * second import plan (destination = first import, source = second mapping)
 * contains NO new INSERTs and NO Developer keep-both duplicates.
 */
class LegacySnapshotMergeIdempotenceTest {

    private fun loadFixture(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("legacy-fixtures/phase5a/$name.rakvault")
            ?.use { it.readBytes() }
            ?: error("fixture not found: $name")

    private fun mapped(name: String, password: String): VaultSnapshot {
        val bytes = loadFixture(name)
        val bundle = LegacyRakVaultImporter().import(bytes, password)
        val fingerprint = LegacyVaultSnapshotMapper.fingerprintOfEncryptedBytes(bytes)
        val snapshot = LegacyVaultSnapshotMapper.map(bundle, fingerprint)
        PackageValidator.validate(snapshot)
        return snapshot
    }

    private fun assertIdempotent(name: String, password: String) {
        val first = mapped(name, password)
        val second = mapped(name, password)

        val plan = MergePlanner.plan(destination = first, source = second)

        assertEquals(0, plan.summary.inserted)
        assertEquals(0, plan.summary.conflicts)
        assertEquals(0, plan.summary.stateDivergences)

        // Everything in the second mapping is a duplicate / unchanged.
        val totalRecords = first.accounts.sumOf { it.totpCredentials.size + it.recoveryCodeSets.size } +
            first.developerEntries.size
        assertTrue(plan.summary.duplicates + plan.summary.unchanged >= totalRecords)
    }

    @Test
    fun `schema1 re-import is idempotent`() {
        assertIdempotent("phase5a_schema1_basic", "test-password-1")
    }

    @Test
    fun `schema2 with developer entries re-import is idempotent`() {
        assertIdempotent("phase5a_schema2_dev", "test-password-2")
    }

    @Test
    fun `schema3 full vault re-import is idempotent`() {
        assertIdempotent("phase5a_schema3_full", "test-password-3")
    }
}
