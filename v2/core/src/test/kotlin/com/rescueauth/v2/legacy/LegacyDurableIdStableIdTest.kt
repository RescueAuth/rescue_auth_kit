package com.rescueauth.v2.legacy

import com.rescueauth.v2.export.PackageValidator
import com.rescueauth.v2.export.VaultSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5A (CR fix) — durable-id-first stableId contract (ADR-0010 §4).
 *
 * Legacy v1 objects carry **durable persisted ids** (UUID v4, minted at
 * creation, stored in the vault JSON). The v2 stableId MUST be derived from
 * that durable identity, NOT from the encrypted source-file fingerprint, so
 * that the same logical object re-encrypted into two different `.rakvault`
 * backups (different encrypted bytes → different source fingerprints) yields
 * the SAME stableId.
 *
 * The two `frozen_v1_producer_schema3*` fixtures were produced by the frozen
 * v1.2.0 implementation (verbatim `vault_crypto.dart` + `vault_models.dart`)
 * and are two DIFFERENT encryptions of the SAME logical vault (same durable
 * ids, different random salt/nonce → different sourceFingerprint).
 */
class LegacyDurableIdStableIdTest {

    private fun loadFixture(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("legacy-fixtures/phase5a/$name.rakvault")
            ?.use { it.readBytes() }
            ?: error("fixture not found: $name")

    private fun mapped(name: String, password: String): Pair<ByteArray, VaultSnapshot> {
        val bytes = loadFixture(name)
        val bundle = LegacyRakVaultImporter().import(bytes, password)
        val fingerprint = LegacyVaultSnapshotMapper.fingerprintOfEncryptedBytes(bytes)
        val snapshot = LegacyVaultSnapshotMapper.map(bundle, fingerprint)
        PackageValidator.validate(snapshot)
        return bytes to snapshot
    }

    private fun allStableIds(s: VaultSnapshot): List<String> {
        val out = mutableListOf<String>()
        for (a in s.accounts) {
            out += a.stableId
            out += a.totpCredentials.map { it.stableId }
            out += a.recoveryCodeSets.flatMap { set ->
                listOf(set.stableId) + set.codes.map { it.stableId }
            }
        }
        out += s.developerEntries.map { it.stableId }
        return out
    }

    private fun assertSetsEqual(a: List<String>, b: List<String>) {
        assertEquals(a.sorted(), b.sorted())
    }

    // ------------------------------------------------------------------
    // 1. Same exact file twice → identical stableIds
    // ------------------------------------------------------------------

    @Test
    fun `same exact file mapped twice yields identical stableIds`() {
        val (_, s1) = mapped("frozen_v1_producer_schema3", "test-password-frozen")
        val (_, s2) = mapped("frozen_v1_producer_schema3", "test-password-frozen")
        assertSetsEqual(allStableIds(s1), allStableIds(s2))
    }

    // ------------------------------------------------------------------
    // 2. Two different encrypted backups of the SAME logical object (same
    //    durable id) → resulting v2 stableIds must be identical
    // ------------------------------------------------------------------

    @Test
    fun `two different encrypted backups with same durable ids yield identical stableIds`() {
        val (b1, s1) = mapped("frozen_v1_producer_schema3", "test-password-frozen")
        val (b2, s2) = mapped("frozen_v1_producer_schema3_alt_backup", "test-password-frozen")

        // The encrypted bytes differ (different random salt/nonce) → different
        // source fingerprints. This is the crux of the durable-id contract.
        assertNotEquals(
            LegacyVaultSnapshotMapper.fingerprintOfEncryptedBytes(b1),
            LegacyVaultSnapshotMapper.fingerprintOfEncryptedBytes(b2),
        )
        assertNotEquals(b1.toList(), b2.toList())

        // ...but the v2 stableIds for the SAME logical objects must be equal.
        assertSetsEqual(allStableIds(s1), allStableIds(s2))
    }

    // ------------------------------------------------------------------
    // 3. Different durable ids → no collision
    // ------------------------------------------------------------------

    @Test
    fun `different durable ids never collide`() {
        // phase5a_schema3_full has its own durable ids (prov-1/acc-1/cred-1...)
        // while the frozen producer fixture uses prov-frozen-1/acc-frozen-1...
        val (_, s1) = mapped("phase5a_schema3_full", "test-password-3")
        val (_, s2) = mapped("frozen_v1_producer_schema3", "test-password-frozen")

        val ids1 = allStableIds(s1).toSet()
        val ids2 = allStableIds(s2).toSet()
        assertTrue(ids1.intersect(ids2).isEmpty())
    }

    // ------------------------------------------------------------------
    // 4. Developer entries covered (all five types)
    // ------------------------------------------------------------------

    @Test
    fun `developer entry stableIds are derived from durable developer ids`() {
        val (_, s1) = mapped("frozen_v1_producer_schema3", "test-password-frozen")
        val (_, s2) = mapped("frozen_v1_producer_schema3_alt_backup", "test-password-frozen")

        assertEquals(5, s1.developerEntries.size)
        assertEquals(5, s2.developerEntries.size)

        // Same durable developer ids across the two backups → identical stableIds.
        assertSetsEqual(
            s1.developerEntries.map { it.stableId },
            s2.developerEntries.map { it.stableId },
        )

        // All developer stableIds are namespaced + never contain plaintext.
        for (entry in s1.developerEntries) {
            assertTrue(entry.stableId.startsWith("legacy:developer:"))
        }
    }

    // ------------------------------------------------------------------
    // 5. Recovery codes use the structural fallback (parent durable id + index)
    // ------------------------------------------------------------------

    @Test
    fun `recovery code stableIds are structural under the durable set id`() {
        val (_, s1) = mapped("frozen_v1_producer_schema3", "test-password-frozen")
        val (_, s2) = mapped("frozen_v1_producer_schema3_alt_backup", "test-password-frozen")

        val set1 = s1.accounts.flatMap { it.recoveryCodeSets }.single()
        val set2 = s2.accounts.flatMap { it.recoveryCodeSets }.single()

        // The set stableId is durable-id-derived; the code stableIds are
        // deterministic structural children.
        assertEquals(set1.stableId, set2.stableId)
        assertSetsEqual(set1.codes.map { it.stableId }, set2.codes.map { it.stableId })
        assertEquals(set1.codes.size, 3)

        // Codes carry no plaintext recovery-code values.
        for (code in set1.codes) {
            assertTrue(!code.stableId.contains("AAAA-BBBB-CCCC"))
            assertTrue(code.stableId.startsWith("legacy:recovery_code:"))
        }
    }

    // ------------------------------------------------------------------
    // 6. stableId never contains plaintext secret
    // ------------------------------------------------------------------

    @Test
    fun `stableId never contains plaintext secret or source fingerprint`() {
        val (bytes, snapshot) = mapped("frozen_v1_producer_schema3", "test-password-frozen")
        val fingerprint = LegacyVaultSnapshotMapper.fingerprintOfEncryptedBytes(bytes)

        val stableIds = allStableIds(snapshot)
        assertTrue(stableIds.all { it.startsWith("legacy:") })

        val secrets = listOf(
            "test-password-frozen",
            "JBSWY3DPEHPK3PXP",      // TOTP secret
            "sk-test-123",            // API key
            "secret-abc",             // API secret
            "AAECAwQFBgc=",           // keystore bytes base64
            "-----BEGIN OPENSSH",     // SSH private key
        )
        for (s in stableIds) {
            for (secret in secrets) {
                assertTrue("stableId must not contain plaintext secret: $s", !s.contains(secret))
            }
            // The stableId must NOT embed the source file fingerprint either
            // (file identity ≠ object identity).
            assertTrue("stableId must not embed sourceFingerprint", !s.contains(fingerprint))
        }
    }
}
