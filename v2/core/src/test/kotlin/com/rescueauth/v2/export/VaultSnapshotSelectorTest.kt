package com.rescueauth.v2.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 4 P5 — shared selection engine core tests (Issue #20 §24).
 *
 * Covers the 13 required selection-core cases:
 *
 * 1. full vault selection unchanged
 * 2. authenticator-only excludes Developer
 * 3. developer-only excludes Authenticator
 * 4. Provider selection includes all descendants
 * 5. Account selection includes descendants + Provider parent metadata
 * 6. single TOTP includes only itself + parents
 * 7. Recovery Set includes the whole set + parents
 * 8. single Developer entry only selects itself
 * 9. Generic Secret field cannot be partially selected
 * 10. missing/stale stableId fails explicitly
 * 11. output has no orphan account/provider reference
 * 12. deterministic ordering / canonicalization preserved
 * 13. all five Developer types supported
 */
class VaultSnapshotSelectorTest {

    // ------------------------------------------------------------------
    // Snapshot builders (deterministic)
    // ------------------------------------------------------------------

    private fun snapshot(): VaultSnapshot {
        val accA = SnapshotBuilder.account(
            "acc-a",
            serviceName = "GitHub",
            accountName = "alice",
            totps = listOf(
                SnapshotBuilder.totp("totp-a1"),
                SnapshotBuilder.totp("totp-a2", secret = "4F6VS6KX3UXWY2FQ"),
            ),
            recoverySets = listOf(
                SnapshotBuilder.recoverySet(
                    "set-a1",
                    codes = listOf(
                        SnapshotBuilder.recoveryCode("c-a1-1", "111-111", status = "UNUSED"),
                        SnapshotBuilder.recoveryCode("c-a1-2", "222-222", status = "USED", usedAt = "2024-01-01"),
                    ),
                ),
            ),
        )
        val accB = SnapshotBuilder.account(
            "acc-b",
            serviceName = "GitHub",
            accountName = "bob",
            totps = listOf(SnapshotBuilder.totp("totp-b1", secret = "NBSWY3DPEHPK3PXP")),
        )
        val accC = SnapshotBuilder.account(
            "acc-c",
            serviceName = "GitLab",
            accountName = "carol",
            totps = listOf(SnapshotBuilder.totp("totp-c1", secret = "JBSWY3DPEHPK3PXP")),
        )
        return VaultSnapshot(
            accounts = listOf(accA, accB, accC),
            developerEntries = listOf(
                SnapshotBuilder.signingKey("dev-sk"),
                SnapshotBuilder.apiCredential("dev-api"),
                SnapshotBuilder.sshKey("dev-ssh"),
                SnapshotBuilder.envVarSet("dev-env"),
                SnapshotBuilder.genericSecret(
                    "dev-generic",
                    fields = listOf(
                        VaultKeyValue("token", "t-1"),
                        VaultKeyValue("endpoint", "https://x"),
                    ),
                ),
            ),
            scope = SnapshotScope.FULL_VAULT,
        )
    }

    // ---- 1. full vault selection unchanged ----

    @Test
    fun `full vault selection is unchanged`() {
        val snap = snapshot()
        val full = VaultSnapshotSelector.fullVault(snap)
        assertEquals(SnapshotScope.FULL_VAULT, full.scope)
        assertEquals(snap.accounts, full.accounts)
        assertEquals(snap.developerEntries, full.developerEntries)
    }

    // ---- 2. authenticator-only excludes Developer ----

    @Test
    fun `authenticator only excludes developer`() {
        val auth = VaultSnapshotSelector.authenticatorOnly(snapshot())
        assertEquals(SnapshotScope.AUTHENTICATOR_ONLY, auth.scope)
        assertEquals(3, auth.accounts.size)
        assertTrue(auth.developerEntries.isEmpty())
    }

    // ---- 3. developer-only excludes Authenticator ----

    @Test
    fun `developer only excludes authenticator`() {
        val dev = VaultSnapshotSelector.developerOnly(snapshot())
        assertEquals(SnapshotScope.DEVELOPER_ONLY, dev.scope)
        assertEquals(5, dev.developerEntries.size)
        assertTrue(dev.accounts.isEmpty())
    }

    // ---- 4. Provider selection includes all descendants ----

    @Test
    fun `provider selection includes all descendants`() {
        val snap = snapshot()
        // Provider = GitHub (serviceName grouping) → expands to acc-a + acc-b.
        val provider = VaultSnapshotSelector.selectableItems(snap).providers
            .first { it.serviceName == "GitHub" }
        val selection = SelectedItemSet(selectedAccountStableIds = provider.expandToStableIds())

        val out = VaultSnapshotSelector.selected(snap, selection)
        assertEquals(SnapshotScope.SELECTED_ITEMS, out.scope)
        assertEquals(setOf("acc-a", "acc-b"), out.accounts.map { it.stableId }.toSet())
        // All descendants included.
        val a = out.accounts.first { it.stableId == "acc-a" }
        assertEquals(setOf("totp-a1", "totp-a2"), a.totpCredentials.map { it.stableId }.toSet())
        assertEquals(setOf("set-a1"), a.recoveryCodeSets.map { it.stableId }.toSet())
        val b = out.accounts.first { it.stableId == "acc-b" }
        assertEquals(setOf("totp-b1"), b.totpCredentials.map { it.stableId }.toSet())
        // GitLab (carol) NOT included.
        assertTrue(out.accounts.none { it.stableId == "acc-c" })
        // Developer section excluded (provider selection is Authenticator-only).
        assertTrue(out.developerEntries.isEmpty())
    }

    // ---- 5. Account selection includes descendants + Provider parent metadata ----

    @Test
    fun `account selection includes descendants and provider metadata`() {
        val snap = snapshot()
        val selection = SelectedItemSet(selectedAccountStableIds = setOf("acc-a"))
        val out = VaultSnapshotSelector.selected(snap, selection)

        assertEquals(listOf("acc-a"), out.accounts.map { it.stableId })
        val a = out.accounts.single()
        // Provider parent metadata is carried by the account row.
        assertEquals("GitHub", a.serviceName)
        assertEquals("alice", a.accountName)
        // All descendants included.
        assertEquals(setOf("totp-a1", "totp-a2"), a.totpCredentials.map { it.stableId }.toSet())
        assertEquals(setOf("set-a1"), a.recoveryCodeSets.map { it.stableId }.toSet())
        // Sibling accounts not included.
        assertTrue(out.accounts.none { it.stableId == "acc-b" })
        assertTrue(out.developerEntries.isEmpty())
    }

    // ---- 6. single TOTP includes only itself + parents ----

    @Test
    fun `single totp includes only itself and parents`() {
        val snap = snapshot()
        val selection = SelectedItemSet(selectedTotpStableIds = setOf("totp-a1"))
        val out = VaultSnapshotSelector.selected(snap, selection)

        assertEquals(listOf("acc-a"), out.accounts.map { it.stableId })
        val a = out.accounts.single()
        assertEquals("GitHub", a.serviceName)
        // Only the selected TOTP — Account A's other TOTP/Recovery NOT included.
        assertEquals(listOf("totp-a1"), a.totpCredentials.map { it.stableId })
        assertTrue(a.recoveryCodeSets.isEmpty())
        assertTrue(out.developerEntries.isEmpty())
    }

    // ---- 7. Recovery Set includes whole set + parents ----

    @Test
    fun `recovery set includes whole set and parents`() {
        val snap = snapshot()
        val selection = SelectedItemSet(selectedRecoverySetStableIds = setOf("set-a1"))
        val out = VaultSnapshotSelector.selected(snap, selection)

        assertEquals(listOf("acc-a"), out.accounts.map { it.stableId })
        val a = out.accounts.single()
        val set = a.recoveryCodeSets.single()
        assertEquals("set-a1", set.stableId)
        // The whole set is atomic — both codes incl. the USED one are preserved.
        assertEquals(setOf("c-a1-1", "c-a1-2"), set.codes.map { it.stableId }.toSet())
        assertEquals("USED", set.codes.first { it.stableId == "c-a1-2" }.status)
        assertEquals("2024-01-01", set.codes.first { it.stableId == "c-a1-2" }.usedAt)
        // No TOTP from the parent account sneaks in.
        assertTrue(a.totpCredentials.isEmpty())
    }

    // ---- 8. single Developer entry only selects itself ----

    @Test
    fun `single developer entry selects only itself`() {
        val snap = snapshot()
        val selection = SelectedItemSet(selectedDeveloperStableIds = setOf("dev-api"))
        val out = VaultSnapshotSelector.selected(snap, selection)

        assertTrue(out.accounts.isEmpty())
        assertEquals(listOf("dev-api"), out.developerEntries.map { it.stableId })
        val api = out.developerEntries.single() as VaultApiCredential
        assertEquals("stripe", api.serviceName)
        assertEquals("sk_test_123", api.apiKey)
    }

    // ---- 9. Generic Secret field cannot be partially selected ----

    @Test
    fun `generic secret field cannot be partially selected`() {
        val snap = snapshot()
        val selection = SelectedItemSet(selectedDeveloperStableIds = setOf("dev-generic"))
        val out = VaultSnapshotSelector.selected(snap, selection)

        val generic = out.developerEntries.single() as VaultGenericSecret
        // Whole entry is atomic — all fields preserved.
        assertEquals(2, generic.fields.size)
        // There is no per-field selection identity at all (the API surface has
        // no field-level selector), so a partial field selection is impossible
        // by construction.
        assertFalse(SelectedItemSet::class.java.methods.any {
            it.name.contains("Field", ignoreCase = true)
        })
    }

    // ---- 10. missing/stale stableId fails explicitly ----

    @Test
    fun `stale stableId fails explicitly`() {
        val snap = snapshot()
        // A stableId that does not exist in the snapshot.
        val selection = SelectedItemSet(selectedTotpStableIds = setOf("totp-DELETED"))
        try {
            VaultSnapshotSelector.selected(snap, selection)
            fail("expected SelectionStaleException")
        } catch (e: SelectionStaleException) {
            assertTrue(e.missingStableIds.any { it.contains("totp-DELETED") })
        }

        // A stale developer entry also fails.
        try {
            VaultSnapshotSelector.selected(
                snap,
                SelectedItemSet(selectedDeveloperStableIds = setOf("dev-GONE")),
            )
            fail("expected SelectionStaleException")
        } catch (e: SelectionStaleException) {
            assertTrue(e.missingStableIds.any { it.contains("dev-GONE") })
        }

        // Empty selection fails explicitly too.
        try {
            VaultSnapshotSelector.selected(snap, SelectedItemSet())
            fail("expected EmptySelectionException")
        } catch (e: EmptySelectionException) {
            // expected
        }
    }

    // ---- 11. output has no orphan account/provider reference ----

    @Test
    fun `selected output has no orphan references`() {
        val snap = snapshot()
        // Select a TOTP whose account must be auto-included; the output must
        // contain the account (never a dangling child).
        val out = VaultSnapshotSelector.selected(
            snap,
            SelectedItemSet(selectedTotpStableIds = setOf("totp-c1")),
        )
        assertEquals(listOf("acc-c"), out.accounts.map { it.stableId })
        val c = out.accounts.single()
        assertEquals("GitLab", c.serviceName)
        assertEquals(listOf("totp-c1"), c.totpCredentials.map { it.stableId })

        // Every kept child's account is present (structural invariant).
        for (account in out.accounts) {
            assertTrue(account.totpCredentials.all { it.stableId.isNotBlank() })
            assertTrue(account.recoveryCodeSets.all { it.stableId.isNotBlank() })
        }
        // PackageValidator accepts the produced snapshot (scope consistency).
        PackageValidator.validateSnapshot(out)
    }

    // ---- 12. deterministic ordering / canonicalization preserved ----

    @Test
    fun `deterministic ordering is preserved`() {
        val snap = snapshot()
        val selection = SelectedItemSet(
            selectedAccountStableIds = setOf("acc-c", "acc-a", "acc-b"),
            selectedDeveloperStableIds = setOf("dev-generic", "dev-sk"),
        )
        val out = VaultSnapshotSelector.selected(snap, selection)

        // Source ordering is preserved (not re-sorted / reordered by the set).
        assertEquals(listOf("acc-a", "acc-b", "acc-c"), out.accounts.map { it.stableId })
        assertEquals(listOf("dev-sk", "dev-generic"), out.developerEntries.map { it.stableId })

        // Selecting the same items in a different order yields the same result.
        val selection2 = SelectedItemSet(
            selectedAccountStableIds = setOf("acc-b", "acc-a", "acc-c"),
            selectedDeveloperStableIds = setOf("dev-sk", "dev-generic"),
        )
        val out2 = VaultSnapshotSelector.selected(snap, selection2)
        assertEquals(out, out2)
    }

    // ---- 13. all five Developer types supported ----

    @Test
    fun `all five developer types are supported by selection`() {
        val snap = snapshot()
        val selection = SelectedItemSet(
            selectedDeveloperStableIds = setOf("dev-sk", "dev-api", "dev-ssh", "dev-env", "dev-generic"),
        )
        val out = VaultSnapshotSelector.selected(snap, selection)
        assertEquals(5, out.developerEntries.size)

        // Exact logical preservation of every type.
        val byType = out.developerEntries.map { it::class }.toSet()
        assertTrue(byType.contains(VaultAndroidSigningKey::class))
        assertTrue(byType.contains(VaultApiCredential::class))
        assertTrue(byType.contains(VaultSshKey::class))
        assertTrue(byType.contains(VaultEnvironmentVariableSet::class))
        assertTrue(byType.contains(VaultGenericSecret::class))

        // Round-trip field preservation: selected output is an exact subset.
        for (entry in out.developerEntries) {
            val src = snap.developerEntries.first { it.stableId == entry.stableId }
            assertEquals(
                Canonicalization.developerLogicalFingerprint(src),
                Canonicalization.developerLogicalFingerprint(entry),
            )
        }
    }

    // ------------------------------------------------------------------
    // Extra: selectable enumeration is safe (no secrets) & provider expand
    // ------------------------------------------------------------------

    @Test
    fun `selectable items expose no secrets`() {
        val snap = snapshot()
        val items = VaultSnapshotSelector.selectableItems(snap)
        val text = items.toString()

        assertFalse(text.contains("sk_test_123"))
        assertFalse(text.contains("JBSWY3DPEHPK3PXP"))
        assertFalse(text.contains("111-111"))
        assertFalse(text.contains("MIIE"))
        assertFalse(text.contains("store-pass"))
        assertTrue(items.hasAuthenticator)
        assertTrue(items.hasDeveloper)
        assertEquals(3, items.accountCount)
        assertEquals(4, items.totpCount)
        assertEquals(1, items.recoverySetCount)
        assertEquals(5, items.developerCount)
    }

    @Test
    fun `everything item set selects the whole snapshot`() {
        val snap = snapshot()
        val everything = VaultSnapshotSelector.everything(snap)
        val out = VaultSnapshotSelector.selected(snap, everything)
        assertEquals(SnapshotScope.SELECTED_ITEMS, out.scope)
        assertEquals(snap.accounts, out.accounts)
        assertEquals(snap.developerEntries, out.developerEntries)
    }

    @Test
    fun `selection digest is deterministic and bound to content`() {
        val snap = snapshot()
        val sel1 = SelectedItemSet(selectedAccountStableIds = setOf("acc-a", "acc-b"))
        val sel2 = SelectedItemSet(selectedAccountStableIds = setOf("acc-b", "acc-a"))
        val sel3 = SelectedItemSet(selectedAccountStableIds = setOf("acc-a"))
        assertEquals(sel1.digest(), sel2.digest())
        assertTrue(sel1.digest() != sel3.digest())
    }

    // ------------------------------------------------------------------
    // P5 security-boundary CR §4 — canonical selection digest contract
    // ------------------------------------------------------------------

    @Test
    fun `digest is order independent for same logical selection`() {
        // The same logical selection committed in a different UI order yields
        // the same digest (deterministic canonical sort).
        val a = SelectedItemSet(
            selectedAccountStableIds = setOf("acc-a", "acc-b"),
            selectedTotpStableIds = setOf("totp-x"),
            selectedDeveloperStableIds = setOf("dev-1"),
        )
        val b = SelectedItemSet(
            selectedAccountStableIds = setOf("acc-b", "acc-a"),
            selectedTotpStableIds = setOf("totp-x"),
            selectedDeveloperStableIds = setOf("dev-1"),
        )
        assertEquals(a.digest(), b.digest())
    }

    @Test
    fun `digest differs when a different stableId is selected`() {
        val base = SelectedItemSet(selectedTotpStableIds = setOf("totp-a"))
        val other = SelectedItemSet(selectedTotpStableIds = setOf("totp-b"))
        val both = SelectedItemSet(selectedTotpStableIds = setOf("totp-a", "totp-b"))
        assertTrue(base.digest() != other.digest())
        assertTrue(base.digest() != both.digest())
        assertTrue(other.digest() != both.digest())
    }

    @Test
    fun `digest differs for same stableId string under different item kind`() {
        // Same raw stableId string but a different item kind MUST produce a
        // different identity — the kind is part of the canonical encoding.
        val asAccount = SelectedItemSet(selectedAccountStableIds = setOf("abc"))
        val asTotp = SelectedItemSet(selectedTotpStableIds = setOf("abc"))
        val asRecovery = SelectedItemSet(selectedRecoverySetStableIds = setOf("abc"))
        val asDeveloper = SelectedItemSet(selectedDeveloperStableIds = setOf("abc"))
        val digests = setOf(asAccount.digest(), asTotp.digest(), asRecovery.digest(), asDeveloper.digest())
        assertEquals(4, digests.size)
    }

    @Test
    fun `digest encoding is unambiguous for hostile stableIds`() {
        // Length-prefixing keeps the canonical representation unambiguous even
        // when a stableId embeds the record separator / kind syntax.
        val a = SelectedItemSet(
            selectedAccountStableIds = setOf("TOTP:3:abc"),
            selectedTotpStableIds = setOf("abc"),
        )
        val b = SelectedItemSet(
            selectedAccountStableIds = setOf("abc"),
            selectedTotpStableIds = setOf("TOTP:3:abc"),
        )
        // Cross-kind swap: the digest MUST differ because the kind token stays
        // attached to its own record even though the raw strings are swapped.
        assertTrue(a.digest() != b.digest())

        // Newline inside a stableId must not let records merge ambiguously.
        val c = SelectedItemSet(selectedTotpStableIds = setOf("x\ny"))
        val d = SelectedItemSet(selectedTotpStableIds = setOf("x", "y"))
        assertTrue(c.digest() != d.digest())
    }

    @Test
    fun `digest is a fixed length sha256 hex and contains no plaintext secret`() {
        val selection = SelectedItemSet(selectedTotpStableIds = setOf("totp-1"))
        val digest = selection.digest()
        assertEquals(64, digest.length)
        assertTrue(digest.matches(Regex("[0-9a-f]{64}")))
        // Non-secret by construction: only stableIds enter the digest, and the
        // hex representation never contains the plaintext secret.
        assertTrue(!digest.contains("JBSWY3DPEHPK3PXP"))
        assertTrue(!digest.contains("totp-1"))
        assertTrue(!selection.toString().contains("JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun `digest is deterministic across repeated computation`() {
        val selection = SelectedItemSet(
            selectedAccountStableIds = setOf("acc-a"),
            selectedTotpStableIds = setOf("totp-a", "totp-b"),
            selectedRecoverySetStableIds = setOf("set-a"),
            selectedDeveloperStableIds = setOf("dev-1", "dev-2"),
        )
        val first = selection.digest()
        repeat(25) {
            assertEquals(first, selection.digest())
        }
    }
}
