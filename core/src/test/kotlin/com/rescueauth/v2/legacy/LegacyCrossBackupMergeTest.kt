package com.rescueauth.v2.legacy

import com.rescueauth.v2.export.MergePlanner
import com.rescueauth.v2.export.PackageValidator
import com.rescueauth.v2.export.VaultSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5A (CR fix) — cross-backup merge semantics under durable-id stableIds
 * (ADR-0010 §4).
 *
 * Two DIFFERENT encrypted backups of the SAME logical vault (same durable ids,
 * produced by the frozen v1.2.0 implementation with different random
 * salt/nonce) must merge with ZERO new inserts — the second import is fully
 * DUPLICATE / unchanged. This is exactly the scenario the old
 * source-fingerprint-namespaced stableIds could NOT satisfy (different file
 * fingerprint → different stableId → spurious new logical objects).
 */
class LegacyCrossBackupMergeTest {

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

    @Test
    fun `merging two different backups of the same vault inserts nothing`() {
        val first = mapped("frozen_v1_producer_schema3", "test-password-frozen")
        val second = mapped("frozen_v1_producer_schema3_alt_backup", "test-password-frozen")

        val plan = MergePlanner.plan(destination = first, source = second)

        assertEquals(0, plan.summary.inserted)
        assertEquals(0, plan.summary.conflicts)
        assertEquals(0, plan.summary.stateDivergences)

        val totalRecords = first.accounts.sumOf { it.totpCredentials.size + it.recoveryCodeSets.size } +
            first.developerEntries.size
        assertTrue(plan.summary.duplicates + plan.summary.unchanged >= totalRecords)
    }

    @Test
    fun `different logical vaults still insert without collision`() {
        val dest = mapped("frozen_v1_producer_schema3", "test-password-frozen")
        val src = mapped("phase5a_schema3_full", "test-password-3")

        val plan = MergePlanner.plan(destination = dest, source = src)

        // phase5a_schema3_full has completely different durable ids → all INSERT.
        assertTrue(plan.summary.inserted > 0)
        assertEquals(0, plan.summary.conflicts)
    }
}
