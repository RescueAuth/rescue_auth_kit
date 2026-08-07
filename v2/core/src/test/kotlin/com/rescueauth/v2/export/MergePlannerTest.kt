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

    // ------------------------------------------------------------------
    // Developer Vault merge semantics (ROADMAP §8.3)
    // ------------------------------------------------------------------

    // same stableId + FULL LOGICAL PAYLOAD identical -> DUPLICATE
    @Test
    fun developerSameStableIdSameLogicalPayloadIsDuplicate() {
        val dest = VaultSnapshot(
            developerEntries = listOf(SnapshotBuilder.sshKey("k1")),
        )
        val source = VaultSnapshot(
            // Same stableId, byte-identical full logical payload (only pure
            // technical metadata createdAt/updatedAt may differ) -> DUPLICATE.
            developerEntries = listOf(SnapshotBuilder.sshKey("k1")),
        )
        val plan = MergePlanner.plan(dest, source)
        assertEquals(0, plan.summary.inserted)
        assertEquals(1, plan.summary.duplicates)
        assertEquals(0, plan.summary.conflicts)
        assertEquals(MergeDecision.DUPLICATE, plan.developerPlans.single().decision)
        assertEquals("k1", plan.developerPlans.single().matchedDestinationStableId)
    }

    // same stableId + same secret but different title -> CONFLICT
    @Test
    fun developerSameStableIdSameSecretDifferentTitleConflicts() {
        val dest = VaultSnapshot(
            developerEntries = listOf(SnapshotBuilder.genericSecret("g1", title = "prod token", fields = listOf(VaultKeyValue("token", "t-1")))),
        )
        val source = VaultSnapshot(
            developerEntries = listOf(SnapshotBuilder.genericSecret("g1", title = "staging token", fields = listOf(VaultKeyValue("token", "t-1")))),
        )
        val plan = MergePlanner.plan(dest, source)
        assertEquals(0, plan.summary.inserted)
        assertEquals(0, plan.summary.duplicates)
        assertEquals(1, plan.summary.conflicts)
        assertEquals(MergeDecision.CONFLICT, plan.developerPlans.single().decision)
    }

    // same stableId signing key + same keystore + different project/package -> CONFLICT
    @Test
    fun signingKeySameStableIdSameKeystoreDifferentProjectPackageConflicts() {
        val keystore = "AAECAwQFBgc="
        val dest = VaultSnapshot(
            developerEntries = listOf(
                SnapshotBuilder.signingKey(
                    "sk-1", projectName = "app-a", packageName = "com.a.app",
                    keystoreBase64 = keystore,
                ),
            ),
        )
        // Same stableId + same keystore bytes but different project/package.
        val source = VaultSnapshot(
            developerEntries = listOf(
                SnapshotBuilder.signingKey(
                    "sk-1", projectName = "app-b", packageName = "com.b.app",
                    keystoreBase64 = keystore,
                ),
            ),
        )
        val plan = MergePlanner.plan(dest, source)
        assertEquals(0, plan.summary.inserted)
        assertEquals(0, plan.summary.duplicates)
        assertEquals(1, plan.summary.conflicts)
        assertEquals(MergeDecision.CONFLICT, plan.developerPlans.single().decision)
    }

    // same stableId API + same key/secret + different service/account -> CONFLICT
    @Test
    fun apiCredentialSameStableIdSameKeySecretDifferentServiceAccountConflicts() {
        val dest = VaultSnapshot(
            developerEntries = listOf(
                SnapshotBuilder.apiCredential("api-1", serviceName = "stripe", accountName = "alice"),
            ),
        )
        // Same stableId + same apiKey/apiSecret but different service/account.
        val source = VaultSnapshot(
            developerEntries = listOf(
                SnapshotBuilder.apiCredential("api-1", serviceName = "stripe", accountName = "bob"),
            ),
        )
        val plan = MergePlanner.plan(dest, source)
        assertEquals(0, plan.summary.inserted)
        assertEquals(0, plan.summary.duplicates)
        assertEquals(1, plan.summary.conflicts)
        assertEquals(MergeDecision.CONFLICT, plan.developerPlans.single().decision)
    }

    // same stableId SSH + same key + different keyName -> CONFLICT
    @Test
    fun sshKeySameStableIdSameKeyDifferentKeyNameConflicts() {
        val key = "-----BEGIN OPENSSH PRIVATE KEY-----\nSAME\n-----END OPENSSH PRIVATE KEY-----"
        val dest = VaultSnapshot(
            developerEntries = listOf(
                SnapshotBuilder.sshKey("k1", keyName = "prod-server", privateKey = key),
            ),
        )
        // Same stableId + same private key but different keyName.
        val source = VaultSnapshot(
            developerEntries = listOf(
                SnapshotBuilder.sshKey("k1", keyName = "staging-server", privateKey = key),
            ),
        )
        val plan = MergePlanner.plan(dest, source)
        assertEquals(0, plan.summary.inserted)
        assertEquals(0, plan.summary.duplicates)
        assertEquals(1, plan.summary.conflicts)
        assertEquals(MergeDecision.CONFLICT, plan.developerPlans.single().decision)
    }

    // same stableId env + same values + different variable names -> CONFLICT
    @Test
    fun envVarSetSameStableIdSameValuesDifferentVariableNamesConflicts() {
        val dest = VaultSnapshot(
            developerEntries = listOf(
                SnapshotBuilder.envVarSet(
                    "e1", projectName = "service-a",
                    variables = listOf(VaultKeyValue("API_KEY", "same"), VaultKeyValue("URL", "https://x")),
                ),
            ),
        )
        // Same stableId + same values but different variable NAMES.
        val source = VaultSnapshot(
            developerEntries = listOf(
                SnapshotBuilder.envVarSet(
                    "e1", projectName = "service-a",
                    variables = listOf(VaultKeyValue("SECRET", "same"), VaultKeyValue("URL", "https://x")),
                ),
            ),
        )
        val plan = MergePlanner.plan(dest, source)
        assertEquals(0, plan.summary.inserted)
        assertEquals(0, plan.summary.duplicates)
        assertEquals(1, plan.summary.conflicts)
        assertEquals(MergeDecision.CONFLICT, plan.developerPlans.single().decision)
    }

    // same stableId generic + same values + different labels -> CONFLICT
    @Test
    fun genericSecretSameStableIdSameValuesDifferentLabelsConflicts() {
        val dest = VaultSnapshot(
            developerEntries = listOf(
                SnapshotBuilder.genericSecret("g1", fields = listOf(VaultKeyValue("token", "t-1"))),
            ),
        )
        // Same stableId + same value but different field LABEL.
        val source = VaultSnapshot(
            developerEntries = listOf(
                SnapshotBuilder.genericSecret("g1", fields = listOf(VaultKeyValue("other-label", "t-1"))),
            ),
        )
        val plan = MergePlanner.plan(dest, source)
        assertEquals(0, plan.summary.inserted)
        assertEquals(0, plan.summary.duplicates)
        assertEquals(1, plan.summary.conflicts)
        assertEquals(MergeDecision.CONFLICT, plan.developerPlans.single().decision)
    }

    // different stableId + identical sensitive payload -> INSERT / keep both.
    // Identical sensitive payloads do NOT prove the same logical asset: the
    // same API key/secret may be saved for two different service/account
    // purposes, so both records must be kept (never silently dropped).
    @Test
    fun developerDifferentStableIdSamePayloadKeepsBoth() {
        val dest = VaultSnapshot(
            developerEntries = listOf(SnapshotBuilder.apiCredential("api-1", apiKey = "sk_1", apiSecret = "sec-1")),
        )
        val source = VaultSnapshot(
            developerEntries = listOf(SnapshotBuilder.apiCredential("api-2", apiKey = "sk_1", apiSecret = "sec-1")),
        )
        val plan = MergePlanner.plan(dest, source)
        assertEquals(1, plan.summary.inserted)
        assertEquals(0, plan.summary.duplicates)
        assertEquals(0, plan.summary.conflicts)
        assertEquals(MergeDecision.INSERT, plan.developerPlans.single().decision)
        // No cross-stableId matching: source api-2 is inserted as its own record.
        assertEquals(null, plan.developerPlans.single().matchedDestinationStableId)
    }

    // different stableId + different payload -> INSERT / keep both
    @Test
    fun developerDifferentStableIdDifferentPayloadInserts() {
        val dest = VaultSnapshot(
            developerEntries = listOf(SnapshotBuilder.sshKey("k1", keyName = "work", privateKey = "AAA")),
        )
        val source = VaultSnapshot(
            developerEntries = listOf(SnapshotBuilder.sshKey("k2", keyName = "work", privateKey = "BBB")),
        )
        val plan = MergePlanner.plan(dest, source)
        assertEquals(1, plan.summary.inserted)
        assertEquals(0, plan.summary.duplicates)
        assertEquals(0, plan.summary.conflicts)
        assertEquals(MergeDecision.INSERT, plan.developerPlans.single().decision)
        // Same keyName does NOT auto-dedupe (ROADMAP §8.3: 不能因为 title/
        // keyName 相同就认为两份 SSH private key 相同).
        assertEquals(null, plan.developerPlans.single().matchedDestinationStableId)
    }

    // second import of the same developer source is a no-op
    @Test
    fun developerSecondImportIsNoop() {
        val entry = SnapshotBuilder.envVarSet(
            "e1",
            variables = listOf(VaultKeyValue("API_KEY", "x"), VaultKeyValue("URL", "y")),
        )
        val first = MergePlanner.plan(VaultSnapshot(), VaultSnapshot(developerEntries = listOf(entry)))
        assertEquals(1, first.summary.inserted)
        val dest = VaultSnapshot(developerEntries = listOf(entry))
        val second = MergePlanner.plan(dest, VaultSnapshot(developerEntries = listOf(entry)))
        assertEquals(0, second.summary.inserted)
        assertEquals(1, second.summary.duplicates)
        assertEquals(0, second.summary.conflicts)
    }

    // android signing key binary keystore participates in identity
    @Test
    fun developerSigningKeyKeystoreBytesDriveIdentity() {
        val dest = VaultSnapshot(
            developerEntries = listOf(SnapshotBuilder.signingKey("sk-1", keystoreBase64 = "AAECAwQFBgc=")),
        )
        val source = VaultSnapshot(
            developerEntries = listOf(SnapshotBuilder.signingKey("sk-1", keystoreBase64 = "AQIDBAUGBwg=")),
        )
        val plan = MergePlanner.plan(dest, source)
        // Same stableId, different keystore bytes -> CONFLICT (never silently
        // keep destination's keystore nor overwrite it).
        assertEquals(1, plan.summary.conflicts)
        assertEquals(MergeDecision.CONFLICT, plan.developerPlans.single().decision)
    }

    // destination-only developer entries are never deleted
    @Test
    fun developerDestinationNeverDeleted() {
        val dest = VaultSnapshot(
            developerEntries = listOf(
                SnapshotBuilder.genericSecret("g1", fields = listOf(VaultKeyValue("a", "1"))),
            ),
        )
        val source = VaultSnapshot(developerEntries = emptyList())
        val plan = MergePlanner.plan(dest, source)
        assertEquals(0, plan.summary.inserted)
        assertEquals(0, plan.summary.duplicates)
        assertEquals(0, plan.summary.conflicts)
        assertEquals(1, plan.summary.unchanged)
        assertTrue(plan.developerPlans.isEmpty())
    }

    // ------------------------------------------------------------------
    // Different stableId + identical sensitive payload -> keep both
    // (conservative merge: sensitive payload alone is NOT a logical identity)
    // ------------------------------------------------------------------

    // same keystore bytes + different project/package -> keep both
    @Test
    fun signingKeySameKeystoreDifferentProjectPackageKeepsBoth() {
        val keystore = "AAECAwQFBgc="
        val dest = VaultSnapshot(
            developerEntries = listOf(
                SnapshotBuilder.signingKey(
                    "sk-1", projectName = "app-a", packageName = "com.a.app",
                    keystoreBase64 = keystore,
                ),
            ),
        )
        // Same keystore bytes used by another project/package on the source.
        val source = VaultSnapshot(
            developerEntries = listOf(
                SnapshotBuilder.signingKey(
                    "sk-2", projectName = "app-b", packageName = "com.b.app",
                    keystoreBase64 = keystore,
                ),
            ),
        )
        val plan = MergePlanner.plan(dest, source)
        assertEquals(1, plan.summary.inserted)
        assertEquals(0, plan.summary.duplicates)
        assertEquals(0, plan.summary.conflicts)
        assertEquals(MergeDecision.INSERT, plan.developerPlans.single().decision)
        assertEquals(null, plan.developerPlans.single().matchedDestinationStableId)
    }

    // same SSH private key + different logical usage/name -> keep both
    @Test
    fun sshKeySamePrivateKeyDifferentLogicalUsageKeepsBoth() {
        val key = "-----BEGIN OPENSSH PRIVATE KEY-----\nSAME\n-----END OPENSSH PRIVATE KEY-----"
        val dest = VaultSnapshot(
            developerEntries = listOf(
                SnapshotBuilder.sshKey("k1", keyName = "prod-server", privateKey = key),
            ),
        )
        // Same private key, different logical usage / keyName on the source.
        val source = VaultSnapshot(
            developerEntries = listOf(
                SnapshotBuilder.sshKey("k2", keyName = "staging-server", privateKey = key),
            ),
        )
        val plan = MergePlanner.plan(dest, source)
        assertEquals(1, plan.summary.inserted)
        assertEquals(0, plan.summary.duplicates)
        assertEquals(0, plan.summary.conflicts)
        assertEquals(MergeDecision.INSERT, plan.developerPlans.single().decision)
        assertEquals(null, plan.developerPlans.single().matchedDestinationStableId)
    }

    // same API key/secret + different service/account -> keep both
    @Test
    fun apiCredentialSameSecretDifferentServiceAccountKeepsBoth() {
        val dest = VaultSnapshot(
            developerEntries = listOf(
                SnapshotBuilder.apiCredential("api-1", serviceName = "stripe", accountName = "alice"),
            ),
        )
        // Same apiKey/apiSecret reused for a different service/account.
        val source = VaultSnapshot(
            developerEntries = listOf(
                SnapshotBuilder.apiCredential("api-2", serviceName = "stripe", accountName = "bob"),
            ),
        )
        val plan = MergePlanner.plan(dest, source)
        assertEquals(1, plan.summary.inserted)
        assertEquals(0, plan.summary.duplicates)
        assertEquals(0, plan.summary.conflicts)
        assertEquals(MergeDecision.INSERT, plan.developerPlans.single().decision)
        assertEquals(null, plan.developerPlans.single().matchedDestinationStableId)
    }

    // env sets with identical values but different project/name -> keep both
    @Test
    fun envVarSetSameValuesDifferentProjectKeepsBoth() {
        val vars = listOf(VaultKeyValue("API_KEY", "same"), VaultKeyValue("URL", "https://x"))
        val dest = VaultSnapshot(
            developerEntries = listOf(
                SnapshotBuilder.envVarSet("e1", projectName = "service-a", variables = vars),
            ),
        )
        // Identical variable values, different project name.
        val source = VaultSnapshot(
            developerEntries = listOf(
                SnapshotBuilder.envVarSet("e2", projectName = "service-b", variables = vars),
            ),
        )
        val plan = MergePlanner.plan(dest, source)
        assertEquals(1, plan.summary.inserted)
        assertEquals(0, plan.summary.duplicates)
        assertEquals(0, plan.summary.conflicts)
        assertEquals(MergeDecision.INSERT, plan.developerPlans.single().decision)
        assertEquals(null, plan.developerPlans.single().matchedDestinationStableId)
    }

    // generic secrets with identical values but different labels -> keep both
    @Test
    fun genericSecretSameValuesDifferentLabelsKeepsBoth() {
        val fields = listOf(VaultKeyValue("token", "t-1"))
        val dest = VaultSnapshot(
            developerEntries = listOf(
                SnapshotBuilder.genericSecret("g1", title = "prod token", fields = fields),
            ),
        )
        // Identical field values, different title/label semantics.
        val source = VaultSnapshot(
            developerEntries = listOf(
                SnapshotBuilder.genericSecret("g2", title = "staging token", fields = fields),
            ),
        )
        val plan = MergePlanner.plan(dest, source)
        assertEquals(1, plan.summary.inserted)
        assertEquals(0, plan.summary.duplicates)
        assertEquals(0, plan.summary.conflicts)
        assertEquals(MergeDecision.INSERT, plan.developerPlans.single().decision)
        assertEquals(null, plan.developerPlans.single().matchedDestinationStableId)
    }

    // ------------------------------------------------------------------
    // Recovery used/unused divergence (user state, never silently dropped)
    // ------------------------------------------------------------------

    // destination=UNUSED, source=USED -> surfaced as state divergence, not a
    // silent DUPLICATE that keeps destination pretending nothing changed.
    @Test
    fun recoveryUsedUnusedDivergenceSurfacedNotSilentlyKept() {
        val dest = snapshot(
            SnapshotBuilder.account(
                "acc-1", "GitHub", "alice",
                recoverySets = listOf(
                    SnapshotBuilder.recoverySet(
                        "set-1", "Backup codes",
                        codes = listOf(SnapshotBuilder.recoveryCode("c1", "AAAA-BBBB", status = "UNUSED")),
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
                        codes = listOf(SnapshotBuilder.recoveryCode("c1", "AAAA-BBBB", status = "USED", usedAt = "2024-02-01T00:00:00Z")),
                    )
                ),
            )
        )
        val plan = MergePlanner.plan(dest, source)
        // Set-level: same values -> DUPLICATE (content identical).
        assertEquals(1, plan.summary.duplicates)
        assertEquals(0, plan.summary.conflicts)
        // BUT the used/unused divergence is reported explicitly.
        assertEquals(1, plan.summary.stateDivergences)
        val codePlan = plan.accountPlans.single().recoverySetPlans.single().codePlans.single()
        assertTrue(codePlan.stateDivergence != null)
        assertEquals("UNUSED", codePlan.stateDivergence!!.destinationStatus)
        assertEquals("USED", codePlan.stateDivergence!!.sourceStatus)
        assertEquals("2024-02-01T00:00:00Z", codePlan.stateDivergence!!.sourceUsedAt)
    }

    // destination=USED, source=UNUSED -> divergence surfaced; cannot un-use.
    @Test
    fun recoveryUsedToUnusedDivergenceSurfaced() {
        val dest = snapshot(
            SnapshotBuilder.account(
                "acc-1", "GitHub", "alice",
                recoverySets = listOf(
                    SnapshotBuilder.recoverySet(
                        "set-1", "Backup codes",
                        codes = listOf(SnapshotBuilder.recoveryCode("c1", "AAAA-BBBB", status = "USED", usedAt = "t")),
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
                        codes = listOf(SnapshotBuilder.recoveryCode("c1", "AAAA-BBBB", status = "UNUSED")),
                    )
                ),
            )
        )
        val plan = MergePlanner.plan(dest, source)
        assertEquals(1, plan.summary.stateDivergences)
        val div = plan.accountPlans.single().recoverySetPlans.single().codePlans.single().stateDivergence!!
        assertEquals("USED", div.destinationStatus)
        assertEquals("UNUSED", div.sourceStatus)
    }

    // identical state (both UNUSED) -> no divergence, clean duplicate
    @Test
    fun recoveryIdenticalStateNoDivergence() {
        val dest = snapshot(
            SnapshotBuilder.account(
                "acc-1", "GitHub", "alice",
                recoverySets = listOf(
                    SnapshotBuilder.recoverySet(
                        "set-1", "Backup codes",
                        codes = listOf(SnapshotBuilder.recoveryCode("c1", "AAAA-BBBB", status = "UNUSED")),
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
                        codes = listOf(SnapshotBuilder.recoveryCode("c1", "AAAA-BBBB", status = "UNUSED")),
                    )
                ),
            )
        )
        val plan = MergePlanner.plan(dest, source)
        assertEquals(1, plan.summary.duplicates)
        assertEquals(0, plan.summary.stateDivergences)
    }

    // independent-device sets (different stableIds) still surface state diffs
    @Test
    fun recoveryStateDivergenceAcrossIndependentStableIds() {
        val dest = snapshot(
            SnapshotBuilder.account(
                "acc-1", "GitHub", "alice",
                recoverySets = listOf(
                    SnapshotBuilder.recoverySet(
                        "set-1", "Backup codes",
                        codes = listOf(SnapshotBuilder.recoveryCode("c1", "AAAA-BBBB", status = "UNUSED")),
                    )
                ),
            )
        )
        val source = snapshot(
            SnapshotBuilder.account(
                "acc-2", "GitHub", "alice",
                recoverySets = listOf(
                    SnapshotBuilder.recoverySet(
                        "set-2", "Backup codes",
                        codes = listOf(SnapshotBuilder.recoveryCode("c9", "AAAA-BBBB", status = "USED")),
                    )
                ),
            )
        )
        val plan = MergePlanner.plan(dest, source)
        assertEquals(1, plan.summary.duplicates)
        assertEquals(1, plan.summary.stateDivergences)
    }
}
