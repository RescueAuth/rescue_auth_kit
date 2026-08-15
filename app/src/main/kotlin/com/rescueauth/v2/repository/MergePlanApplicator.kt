package com.rescueauth.v2.repository

import com.rescueauth.v2.database.AuthAccountDao
import com.rescueauth.v2.database.AuthAccountEntity
import com.rescueauth.v2.database.DeveloperEntryDao
import com.rescueauth.v2.database.ImportRecordDao
import com.rescueauth.v2.database.ImportRecordEntity
import com.rescueauth.v2.database.RecoveryCodeDao
import com.rescueauth.v2.database.RecoveryCodeEntity
import com.rescueauth.v2.database.RecoveryCodeSetDao
import com.rescueauth.v2.database.RecoveryCodeSetEntity
import com.rescueauth.v2.database.TotpCredentialDao
import com.rescueauth.v2.database.TotpCredentialEntity
import com.rescueauth.v2.export.AccountAction
import com.rescueauth.v2.export.AccountMergePlan
import com.rescueauth.v2.export.MergeDecision
import com.rescueauth.v2.export.MergePlan
import com.rescueauth.v2.export.VaultAccount
import com.rescueauth.v2.export.VaultPackagePayload
import com.rescueauth.v2.export.VaultRecoveryCode
import com.rescueauth.v2.export.VaultRecoveryCodeSet
import com.rescueauth.v2.export.VaultSnapshot
import com.rescueauth.v2.export.VaultTotpCredential

/**
 * Phase 3C transactional apply boundary.
 *
 * Executes an already-planned [MergePlan] against the local encrypted Vault
 * inside a single Room transaction. It NEVER plans, never re-derives merge
 * decisions and never touches the legacy / codec layers — it only receives a
 * validated logical snapshot + its plan (ROADMAP §9 isolation, see
 * [VaultRepository.applyMergePlan]).
 *
 * ## API contract (mirrors the MergePlan model)
 *
 * - every **INSERT** is applied (account inserted only when it must carry an
 *   INSERT; child records attach to the resolved destination parent);
 * - every **DUPLICATE** is a no-op (no new stableId, no rewrite);
 * - every **CONFLICT** **blocks the whole apply**: the result carries
 *   `blocked = true` and the transaction rolls back so nothing is written —
 *   Phase 3D surfaces the conflicts for user resolution, never auto-resolves
 *   (source/destination/last-write-wins are forbidden).
 * - **Recovery used/unused divergence** also blocks: `stateDivergences` are
 *   real user state and must not be silently dropped or silently applied.
 *   The preflight counts them and refuses the apply before any write.
 * - destination-only records are never deleted.
 *
 * ## Parent / child identity mapping
 *
 * A source child (TOTP / recovery set) whose parent account is a semantic
 * duplicate of a destination account must attach to the RESOLVED destination
 * account, never to a re-created duplicate:
 *
 * - `ResolvedProviderMapping` = source account stableId → destination account
 *   stableId that carries its inserted children;
 * - `ResolvedAccountMapping` = source account stableId → destination Room `id`
 *   (the actual FK value written into every inserted child row).
 *
 * ## stableId semantics
 *
 * - New objects keep the package/source stableId (lineage survives
 *   export/import), with a fresh Room `id` (local primary key ≠ portable
 *   stableId — ADR-0004).
 * - Semantic duplicates use the existing destination object — no second
 *   stableId is ever minted.
 * - Conflicts are never hidden by minting a new stableId.
 *
 * ## Seams (failure injection)
 *
 * [WriteSeam] is an interface injected by the caller. Production passes
 * [WriteSeam.None]; tests pass a failing seam to verify that a mid-transaction
 * failure rolls back the WHOLE import (provider/account/totp/developer and the
 * ImportRecord). No debug-only failure switch exists in production code.
 */
class MergePlanApplicator(
    private val accountDao: AuthAccountDao,
    private val totpDao: TotpCredentialDao,
    private val recoverySetDao: RecoveryCodeSetDao,
    private val recoveryCodeDao: RecoveryCodeDao,
    private val developerDao: DeveloperEntryDao,
    private val importDao: ImportRecordDao,
    private val seam: WriteSeam = WriteSeam.None,
) {
    /**
     * Preflight: refuse to apply when the plan contains any unresolved
     * CONFLICT or any recovery used/unused state divergence. Returns null
     * when the plan is safe to apply; otherwise a [BlockReason] describing
     * why it is blocked.
     */
    fun preflight(plan: MergePlan): BlockReason? {
        if (plan.summary.conflicts > 0) {
            return BlockReason.ConflictsPresent(plan.summary.conflicts)
        }
        if (plan.summary.stateDivergences > 0) {
            return BlockReason.RecoveryStateDivergence(plan.summary.stateDivergences)
        }
        return null
    }

    /**
     * Applies [plan] against the current database state. MUST be called from
     * inside a Room transaction (the caller owns the transaction boundary and
     * the serialized repository mutex).
     *
     * Returns the granular outcome, including the parent/child identity
     * mapping used ([ResolvedProviderMapping] / [ResolvedAccountMapping]).
     *
     * @throws MergePlanBlockedException when the plan is blocked (conflicts or
     *   recovery state divergence) — the caller must NOT have written anything.
     * @throws MergeApplyException when a write fails mid-apply — the enclosing
     *   transaction rolls back every row written so far (incl. ImportRecord).
     */
    suspend fun apply(
        snapshot: VaultSnapshot,
        plan: MergePlan,
        packageIdentity: String?,
        sourceType: String = "V2_PACKAGE",
    ): ApplyResult {
        preflight(plan)?.let { throw MergePlanBlockedException(it) }

        // ---- resolve destination parents (stableId → Room id) -----------
        val destinationAccountRows = accountDao.listIdAndStableId()
        val destIdByStableId = destinationAccountRows.associate { it.stableId to it.id }

        // ---- accounts to insert: only INSERT_ACCOUNT actions -------------
        val accountsToInsert = plan.accountPlans
            .filter { it.accountAction == AccountAction.INSERT_ACCOUNT }
            .map { it.sourceAccountStableId }
            .toSet()

        val accountsById = snapshot.accounts.associateBy { it.stableId }

        // Insert new accounts (children attach in the per-account pass).
        for (accountStableId in accountsToInsert) {
            val a = accountsById[accountStableId]
                ?: throw MergeApplyException("source account $accountStableId missing from snapshot")
            accountDao.insertAll(
                listOf(
                    AuthAccountEntity(
                        id = accountStableId,
                        stableId = accountStableId,
                        serviceName = a.serviceName,
                        accountName = a.accountName,
                        favorite = a.favorite,
                        notes = a.notes,
                        sortOrder = a.sortOrder,
                        createdAt = a.createdAt,
                        updatedAt = a.updatedAt,
                        legacySourceId = null,
                    )
                )
            )
        }

        // Re-read the id/stableId map so freshly inserted accounts are visible.
        val idByStableIdAfterInsert = accountDao.listIdAndStableId().associate { it.stableId to it.id }

        // ---- resolved parent mappings (source → destination) -------------
        val resolvedProvider = LinkedHashMap<String, String>() // source stableId -> destination stableId
        val resolvedAccount = LinkedHashMap<String, String>()  // source stableId -> destination Room id
        for (ap in plan.accountPlans) {
            when (ap.accountAction) {
                AccountAction.INSERT_ACCOUNT -> {
                    val destId = idByStableIdAfterInsert[ap.sourceAccountStableId]
                        ?: throw MergeApplyException("inserted account ${ap.sourceAccountStableId} missing after insert")
                    resolvedProvider[ap.sourceAccountStableId] = ap.sourceAccountStableId
                    resolvedAccount[ap.sourceAccountStableId] = destId
                }
                AccountAction.USE_EXISTING -> {
                    val targetStableId = ap.targetAccountStableId
                        ?: throw MergeApplyException("USE_EXISTING account ${ap.sourceAccountStableId} has no target")
                    val destId = destIdByStableId[targetStableId]
                        ?: idByStableIdAfterInsert[targetStableId]
                        ?: throw MergeApplyException("destination account $targetStableId not found")
                    resolvedProvider[ap.sourceAccountStableId] = targetStableId
                    resolvedAccount[ap.sourceAccountStableId] = destId
                }
                AccountAction.DUPLICATE_ACCOUNT -> {
                    // No children are inserted for an empty duplicate; nothing to map.
                    resolvedProvider[ap.sourceAccountStableId] = ap.sourceAccountStableId
                    resolvedAccount[ap.sourceAccountStableId] = destIdByStableId[ap.sourceAccountStableId]
                        ?: ap.sourceAccountStableId
                }
            }
        }

        // ---- apply children per account plan -----------------------------
        val insertedTotpStableIds = ArrayList<String>()
        val insertedSetStableIds = ArrayList<String>()
        val insertedCodeStableIds = ArrayList<String>()
        for (ap in plan.accountPlans) {
            applyAccountChildren(
                ap = ap,
                snapshot = snapshot,
                resolvedAccount = resolvedAccount,
            ).let { (totps, sets) ->
                totps.forEach { t ->
                    totpDao.insertAll(listOf(t))
                    insertedTotpStableIds += t.stableId
                }
                sets.forEach { s ->
                    recoverySetDao.insertAll(listOf(s.set))
                    recoveryCodeDao.insertAll(s.codes)
                    insertedSetStableIds += s.set.stableId
                    insertedCodeStableIds += s.codes.map { it.stableId }
                }
            }
        }

        // ---- developer entries (INSERT only; DUPLICATE is a no-op) -------
        seam.beforeDeveloperInsert()
        val developerPlansByStable = plan.developerPlans.associateBy { it.sourceStableId }
        var sortOrder = 0L
        val insertedDeveloperStableIds = ArrayList<String>()
        for (entry in snapshot.developerEntries) {
            val p = developerPlansByStable[entry.stableId]
            if (p?.decision == MergeDecision.INSERT) {
                developerDao.insertAll(listOf(DeveloperMappers.toEntity(entry, sortOrder)))
                insertedDeveloperStableIds += entry.stableId
                sortOrder++
            }
        }

        // ---- ImportRecord (only on success, inside the same transaction) --
        val importedAt = java.time.Instant.now().toString()
        val importRecord = ImportRecordEntity(
            id = java.util.UUID.randomUUID().toString(),
            stableId = java.util.UUID.randomUUID().toString(),
            sourceType = sourceType,
            sourceFingerprint = packageIdentity ?: "merge",
            importedAt = importedAt,
            itemCount = insertedTotpStableIds.size +
                insertedSetStableIds.size +
                insertedCodeStableIds.size +
                insertedDeveloperStableIds.size,
            warningCount = plan.summary.conflicts + plan.summary.stateDivergences,
        )
        importDao.insert(importRecord)

        return ApplyResult(
            insertedAccounts = accountsToInsert.size,
            insertedTotp = insertedTotpStableIds.size,
            insertedRecoverySets = insertedSetStableIds.size,
            insertedRecoveryCodes = insertedCodeStableIds.size,
            insertedDeveloperEntries = insertedDeveloperStableIds.size,
            duplicates = plan.summary.duplicates,
            unchanged = plan.summary.unchanged,
            conflicts = plan.summary.conflicts,
            stateDivergences = plan.summary.stateDivergences,
            importRecordId = importRecord.id,
            resolvedProvider = resolvedProvider,
            resolvedAccount = resolvedAccount,
        )
    }

    /**
     * Builds the Room rows for every INSERT child of one account plan and
     * returns them grouped as (totpRows, recoverySet+codeRows).
     */
    private suspend fun applyAccountChildren(
        ap: AccountMergePlan,
        snapshot: VaultSnapshot,
        resolvedAccount: Map<String, String>,
    ): Pair<List<TotpCredentialEntity>, List<SetWithCodes>> {
        val source = snapshot.accounts.first { it.stableId == ap.sourceAccountStableId }
        val destAccountId = resolvedAccount[ap.sourceAccountStableId]
            ?: throw MergeApplyException("no resolved account for ${ap.sourceAccountStableId}")

        val totpByStable = source.totpCredentials.associateBy { it.stableId }
        val setByStable = source.recoveryCodeSets.associateBy { it.stableId }

        val totpRows = ap.totpPlans
            .filter { it.decision == MergeDecision.INSERT }
            .map { plan ->
                val t = totpByStable[plan.sourceStableId]
                    ?: throw MergeApplyException("source totp ${plan.sourceStableId} missing from snapshot")
                toTotpEntity(t, destAccountId)
            }

        val setRows = ap.recoverySetPlans
            .filter { it.decision == MergeDecision.INSERT }
            .map { plan ->
                val s = setByStable[plan.sourceStableId]
                    ?: throw MergeApplyException("source recovery set ${plan.sourceStableId} missing from snapshot")
                toSetWithCodes(s, destAccountId)
            }

        return totpRows to setRows
    }

    private fun toTotpEntity(t: VaultTotpCredential, accountId: String): TotpCredentialEntity =
        TotpCredentialEntity(
            id = t.stableId,
            stableId = t.stableId,
            accountId = accountId,
            secretBase32 = t.secretBase32,
            algorithm = t.algorithm,
            digits = t.digits,
            periodSeconds = t.periodSeconds,
            createdAt = t.createdAt,
            legacySourceId = null,
        )

    private fun toSetWithCodes(s: VaultRecoveryCodeSet, accountId: String): SetWithCodes {
        val set = RecoveryCodeSetEntity(
            id = s.stableId,
            stableId = s.stableId,
            accountId = accountId,
            title = s.title,
            createdAt = s.createdAt,
            legacySourceId = null,
        )
        val codes = s.codes.sortedBy { it.sortOrder }.mapIndexed { index, c ->
            toRecoveryCodeEntity(c, s.stableId, index)
        }
        return SetWithCodes(set, codes)
    }

    private fun toRecoveryCodeEntity(c: VaultRecoveryCode, setId: String, index: Int): RecoveryCodeEntity =
        RecoveryCodeEntity(
            id = c.stableId,
            stableId = c.stableId,
            setId = setId,
            value = c.value,
            status = c.status,
            usedAt = c.usedAt,
            sortOrder = c.sortOrder,
        )

    private data class SetWithCodes(val set: RecoveryCodeSetEntity, val codes: List<RecoveryCodeEntity>)
}

// ---------------------------------------------------------------------------
// Result / exception model
// ---------------------------------------------------------------------------

/**
 * Granular result of a successfully applied merge. `inserted*` counts every
 * row written; `duplicates`/`unchanged` are carried from the plan for the
 * Phase 3D report; `conflicts`/`stateDivergences` are 0 here (a blocked plan
 * never reaches apply).
 *
 * [resolvedProvider] maps source account stableId → destination account
 * stableId; [resolvedAccount] maps source account stableId → destination
 * Room primary key (the actual parent FK used). Both are populated for every
 * account plan (including empty duplicates, where the mapping is the identity).
 */
data class ApplyResult(
    val insertedAccounts: Int,
    val insertedTotp: Int,
    val insertedRecoverySets: Int,
    val insertedRecoveryCodes: Int,
    val insertedDeveloperEntries: Int,
    val duplicates: Int,
    val unchanged: Int,
    val conflicts: Int,
    val stateDivergences: Int,
    val importRecordId: String?,
    val resolvedProvider: Map<String, String>,
    val resolvedAccount: Map<String, String>,
) {
    val insertedTotal: Int
        get() = insertedAccounts + insertedTotp + insertedRecoverySets +
            insertedRecoveryCodes + insertedDeveloperEntries
}

/** Why a merge plan cannot be applied as-is (Phase 3C conservative policy). */
sealed class BlockReason {
    /** Unresolved CONFLICTs — user must resolve in Phase 3D; never auto-resolve. */
    data class ConflictsPresent(val count: Int) : BlockReason()
    /** Recovery code used/unused divergences — real user state, must be surfaced. */
    data class RecoveryStateDivergence(val count: Int) : BlockReason()
}

/** Thrown by the applicator when the plan is blocked (before any write). */
class MergePlanBlockedException(val reason: BlockReason) :
    Exception("merge apply blocked: $reason")

/** Thrown on a mid-apply write failure — the enclosing transaction rolls back. */
class MergeApplyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Convenience alias for the payload→apply entry point used by the repository. */
typealias ImportApplyResult = ApplyResult

/**
 * Failure-injection seam for Phase 3C transactionality tests.
 *
 * Production always passes [None]. Tests inject a failing implementation to
 * prove that a mid-transaction failure rolls back the WHOLE import. There is
 * deliberately NO debug-only failure switch inside the production apply path.
 */
interface WriteSeam {
    /** Called after all account-level rows are inserted, before developers. */
    fun beforeDeveloperInsert() = Unit

    /** The default no-op seam. */
    object None : WriteSeam
}
