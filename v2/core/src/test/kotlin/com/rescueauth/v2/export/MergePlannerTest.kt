package com.rescueauth.v2.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Core Phase 3A merge semantics — every mandatory scenario from the Phase 3
 * spec, exercised on the pure JVM planner.
 */
class MergePlannerTest {

    private val github = SnapshotBuilder.account(
        "acc-github", "GitHub", "alice@example.com",
        totps = listOf(SnapshotBuilder.totp("totp-1")),
    )
    private val google = SnapshotBuilder.account(
        "acc-google", "Google", "bob@example.com",
        totps = listOf(SnapshotBuilder.totp("totp-2", secret = "4F6VS6KX3UXWY2FQ")),
    )

    private fun snapshot(vararg accounts: VaultAccount) = VaultSnapshot(accounts.toList())

    // 1. empty destination + source -> all insert
    @Test
    fun emptyDestinationAllInserted() {
        val plan = MergePlanner.plan(VaultSnapshot(), snapshot(github, google))
        assertEquals(2, plan.summary.inserted)
        assertEquals(0, plan.summary.duplicates)
        assertEquals(0, plan.summary.conflicts)
        assertEquals(0, plan.summary.unchanged)
        assertTrue(plan.accountPlans.all { it.accountAction == AccountAction.INSERT_ACCOUNT })
        assertTrue(plan.accountPlans.all { p -> p.totpPlans.all { it.decision == MergeDecision.INSERT } })
    }

    // 2. destination + empty source -> destination unchanged
    @Test
    fun emptySourceLeavesDestinationUntouched() {
        val plan = MergePlanner.plan(snapshot(github, google), VaultSnapshot())
        assertEquals(0, plan.summary.inserted)
        assertEquals(0, plan.summary.duplicates)
        assertEquals(0, plan.summary.conflicts)
        // both accounts' totps are destination-only -> unchanged
        assertEquals(2, plan.summary.unchanged)
        assertTrue(plan.accountPlans.isEmpty())
    }

    // 3. completely identical logical package -> all duplicate
    @Test
    fun identicalPackageAllDuplicates() {
        val dest = snapshot(github, google)
        val plan = MergePlanner.plan(dest, dest)
        assertEquals(0, plan.summary.inserted)
        assertEquals(2, plan.summary.duplicates)
        assertEquals(0, plan.summary.conflicts)
        assertEquals(0, plan.summary.unchanged)
        assertTrue(plan.accountPlans.all { it.accountAction == AccountAction.USE_EXISTING })
    }

    // 4. same source merged twice -> second is no-op / all duplicate
    @Test
    fun secondImportOfSameSourceIsNoop() {
        val first = MergePlanner.plan(VaultSnapshot(), snapshot(github, google))
        assertEquals(2, first.summary.inserted)

        // Simulate the destination AFTER the first import (records now exist).
        val mergedDest = snapshot(github, google)
        val second = MergePlanner.plan(mergedDest, snapshot(github, google))
        assertEquals(0, second.summary.inserted)
        assertEquals(2, second.summary.duplicates)
        assertEquals(0, second.summary.conflicts)
    }

    // 5. two vaults with different accounts -> union
    @Test
    fun twoVaultsUnion() {
        val plan = MergePlanner.plan(snapshot(github), snapshot(google))
        // source-only account inserts; destination-only account stays
        assertEquals(1, plan.summary.inserted)
        assertEquals(0, plan.summary.duplicates)
        assertEquals(0, plan.summary.conflicts)
        // destination account (github's totp) remains untouched
        assertEquals(1, plan.summary.unchanged)
    }

    // 6. different stable IDs but identical semantic credential -> duplicate
    @Test
    fun differentStableIdSameSemanticCredentialIsDuplicate() {
        val dest = snapshot(
            SnapshotBuilder.account(
                "acc-a", "GitHub", "alice@example.com",
                totps = listOf(SnapshotBuilder.totp("totp-A", secret = "JBSWY3DPEHPK3PXP")),
            )
        )
        // Second device independently scanned the same QR: different stable id,
        // same secret/params. Account label may differ slightly too.
        val source = snapshot(
            SnapshotBuilder.account(
                "acc-b", "GitHub", "alice@example.com",
                totps = listOf(SnapshotBuilder.totp("totp-B", secret = "j bswy 3dpehp k3pxp")),
            )
        )
        val plan = MergePlanner.plan(dest, source)
        assertEquals(0, plan.summary.inserted)
        assertEquals(1, plan.summary.duplicates)
        assertEquals(0, plan.summary.conflicts)
        // matched by fingerprint: source totp-B maps to dest totp-A
        assertEquals("totp-A", plan.accountPlans.single().totpPlans.single().matchedDestinationStableId)
    }

    // 7. same stableId but different secret -> conflict, no silent overwrite
    @Test
    fun sameStableIdDifferentSecretConflicts() {
        val dest = snapshot(
            SnapshotBuilder.account("acc-1", "GitHub", "alice", totps = listOf(SnapshotBuilder.totp("totp-1")))
        )
        val source = snapshot(
            SnapshotBuilder.account(
                "acc-1", "GitHub", "alice",
                totps = listOf(SnapshotBuilder.totp("totp-1", secret = "DIFFERENTSECRET")),
            )
        )
        val plan = MergePlanner.plan(dest, source)
        assertEquals(0, plan.summary.inserted)
        assertEquals(0, plan.summary.duplicates)
        assertEquals(1, plan.summary.conflicts)
        assertEquals(MergeDecision.CONFLICT, plan.accountPlans.single().totpPlans.single().decision)
    }

    // 8. metadata differs but credential content identical -> stable documented result
    @Test
    fun metadataOnlyDifferenceIsDuplicateDestinationKept() {
        val dest = snapshot(
            SnapshotBuilder.account("acc-1", "GitHub", "alice@example.com", favorite = true,
                totps = listOf(SnapshotBuilder.totp("totp-1")))
        )
        // Source renamed the account label and changed favorite.
        val source = snapshot(
            SnapshotBuilder.account("acc-1", "GitHub Enterprise", "alice@example.com", favorite = false,
                totps = listOf(SnapshotBuilder.totp("totp-1")))
        )
        val plan = MergePlanner.plan(dest, source)
        // Same stableId + same TOTP content -> duplicate. Account metadata
        // (label, favorite) is never silently overwritten.
        assertEquals(1, plan.summary.duplicates)
        assertEquals(0, plan.summary.inserted)
        assertEquals(0, plan.summary.conflicts)
        assertEquals(AccountAction.USE_EXISTING, plan.accountPlans.single().accountAction)
    }

    // 9. recovery-code parent/child identity is preserved
    @Test
    fun recoveryCodeParentChildIdentityPreserved() {
        val dest = snapshot(
            SnapshotBuilder.account(
                "acc-1", "GitHub", "alice",
                recoverySets = listOf(
                    SnapshotBuilder.recoverySet(
                        "set-1", "Backup codes",
                        codes = listOf(
                            SnapshotBuilder.recoveryCode("c1", "AAAA-BBBB", sortOrder = 0),
                            SnapshotBuilder.recoveryCode("c2", "CCCC-DDDD", sortOrder = 1),
                        ),
                    )
                ),
            )
        )
        // New set on the source with different stable ids but same codes.
        val source = snapshot(
            SnapshotBuilder.account(
                "acc-1", "GitHub", "alice",
                recoverySets = listOf(
                    SnapshotBuilder.recoverySet(
                        "set-2", "Backup codes",
                        codes = listOf(
                            SnapshotBuilder.recoveryCode("c9", "AAAA-BBBB", sortOrder = 0),
                            SnapshotBuilder.recoveryCode("c8", "CCCC-DDDD", sortOrder = 1),
                        ),
                    )
                ),
            )
        )
        val plan = MergePlanner.plan(dest, source)
        // Same recovery set fingerprint (title + values) -> duplicate.
        assertEquals(1, plan.summary.duplicates)
        assertEquals(0, plan.summary.conflicts)
        assertEquals(0, plan.summary.inserted)
        // The set is matched to dest set-1; parent/child identity preserved.
        assertEquals("set-1", plan.accountPlans.single().recoverySetPlans.single().matchedDestinationStableId)
    }

    // 12. destination data never deleted because source lacks it
    @Test
    fun destinationNeverDeletedBecauseSourceLacksIt() {
        val dest = snapshot(github, google)
        val source = snapshot(github) // no google
        val plan = MergePlanner.plan(dest, source)
        // google's totp remains unchanged (never deleted).
        assertEquals(1, plan.summary.unchanged)
        // The merge plan model contains NO delete operation whatsoever:
        // the only decisions are INSERT / DUPLICATE / CONFLICT, and account
        // actions never remove a destination record.
        assertTrue(
            plan.accountPlans.flatMap { p -> p.totpPlans.map { it.decision } + p.recoverySetPlans.map { it.decision } }
                .none { it == MergeDecision.CONFLICT || it == MergeDecision.INSERT },
        )
    }

    // 11 + 13. source order changes -> same semantic result; deterministic ordering
    @Test
    fun sourceOrderDoesNotChangeSemantics() {
        val planA = MergePlanner.plan(VaultSnapshot(), snapshot(github, google))
        val planB = MergePlanner.plan(VaultSnapshot(), snapshot(google, github))
        assertEquals(planA.summary, planB.summary)
        // Both plans have 2 insert totps and 2 inserted accounts.
        assertEquals(2, planA.summary.inserted)
        assertEquals(2, planB.summary.inserted)
        // The plan accounts are emitted in source order (deterministic).
        assertEquals(listOf("acc-github", "acc-google"), planA.accountPlans.map { it.sourceAccountStableId })
        assertEquals(listOf("acc-google", "acc-github"), planB.accountPlans.map { it.sourceAccountStableId })
        // Same content regardless of order: collect sorted by stable id.
        val sortKey: (MergePlan) -> List<String> = { p ->
            p.accountPlans.flatMap { ap -> ap.totpPlans.map { it.sourceStableId } }.sorted()
        }
        assertEquals(sortKey(planA), sortKey(planB))
    }

    // 13. deterministic result ordering (same input -> byte-identical plan)
    @Test
    fun deterministicSameInputSameOutput() {
        val dest = snapshot(github)
        val src = snapshot(google)
        val a = MergePlanner.plan(dest, src)
        val b = MergePlanner.plan(dest, src)
        assertEquals(a, b)
    }

    // Recovery set conflict: same stableId, different code values
    @Test
    fun recoverySetSameStableIdDifferentValuesConflicts() {
        val dest = snapshot(
            SnapshotBuilder.account(
                "acc-1", "GitHub", "alice",
                recoverySets = listOf(
                    SnapshotBuilder.recoverySet(
                        "set-1", "Backup codes",
                        codes = listOf(SnapshotBuilder.recoveryCode("c1", "AAAA-BBBB")),
                    )
                ),
            )
        )
        val source = snapshot(
            SnapshotBuilder.account(
                "acc-1", "GitHub", "alice",
                recoverySets = listOf(
                    SnapshotBuilder.recoverySet(
                        "set-1", "Backup codes",
                        codes = listOf(SnapshotBuilder.recoveryCode("c1", "ZZZZ-ZZZZ")),
                    )
                ),
            )
        )
        val plan = MergePlanner.plan(dest, source)
        assertEquals(1, plan.summary.conflicts)
        assertEquals(0, plan.summary.inserted)
        assertEquals(0, plan.summary.duplicates)
    }

    // Inserted children attach to the correct existing account (parent/child).
    @Test
    fun insertedTotpAttachesToExistingAccountByStableId() {
        val dest = snapshot(
            SnapshotBuilder.account("acc-1", "GitHub", "alice") // no totps yet
        )
        val source = snapshot(
            SnapshotBuilder.account(
                "acc-1", "GitHub", "alice",
                totps = listOf(SnapshotBuilder.totp("totp-new")),
            )
        )
        val plan = MergePlanner.plan(dest, source)
        assertEquals(1, plan.summary.inserted)
        assertEquals(AccountAction.USE_EXISTING, plan.accountPlans.single().accountAction)
        assertEquals("acc-1", plan.accountPlans.single().targetAccountStableId)
        assertEquals(MergeDecision.INSERT, plan.accountPlans.single().totpPlans.single().decision)
    }
}
