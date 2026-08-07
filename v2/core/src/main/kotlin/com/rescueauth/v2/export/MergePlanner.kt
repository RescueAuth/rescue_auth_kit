package com.rescueauth.v2.export

/**
 * Deterministic pure merge planner (Phase 3A §MergeEngine).
 *
 * Input: destination logical snapshot + source logical snapshot (the
 * decrypted package). Output: a [MergePlan] that describes, for every source
 * record, whether it should be INSERTed, is a DUPLICATE, or CONFLICTs.
 *
 * The planner NEVER touches a database and NEVER writes anything. The
 * transactional apply to Room happens in Phase 3C; the plan is the
 * machine-readable preview that Phase 3C (and the Phase 3D import preview UI)
 * executes against.
 *
 * ## Record model
 *
 * - A `VaultAccount` is a container. The real records are TOTP credentials
 *   and recovery-code sets (a set's codes are children of the set).
 * - Accounts are matched by stableId first, then by account fingerprint
 *   (serviceName + accountName). A label change is metadata, not a new
 *   credential.
 * - TOTP credentials are matched by stableId first, then by semantic
 *   fingerprint (canonical secret + algorithm + digits + period).
 * - Recovery-code sets are matched by stableId first, then by fingerprint
 *   (title + canonical code values).
 *
 * ## Decisions
 *
 * - INSERT: source record does not exist in destination → it will be added
 *   (account inserted only if it must carry at least one INSERT).
 * - DUPLICATE: destination already holds an identical record (same stableId
 *   and same content, or different stableId but same semantic fingerprint)
 *   → skipped. Metadata-only differences (e.g. recovery-set title, account
 *   favorite/notes) are DUPLICATE with destination values kept — never a
 *   silent overwrite of security content.
 * - CONFLICT: same stableId (same lineage) but security-sensitive content
 *   (secret / TOTP params / recovery-code values) differs → reported, never
 *   last-write-wins, never silently overwritten.
 *
 * ## Invariants (enforced and unit-tested)
 *
 * 1. destination-only records are never deleted;
 * 2. same package imported twice → second plan is all DUPLICATE;
 * 3. deterministic: source order / destination order changes do not change
 *    the plan's semantics (matching uses sorted indexes);
 * 4. validation happens BEFORE planning — an invalid source is rejected
 *    whole, never producing a partial plan.
 */
object MergePlanner {

    fun plan(destination: VaultSnapshot, source: VaultSnapshot): MergePlan {
        // ---- indexes over destination (deterministic) -------------------
        val destTotpByStableId = destination.accounts
            .flatMap { it.totpCredentials }
            .associateBy { it.stableId }

        val destTotpByFingerprint = destination.accounts
            .flatMap { it.totpCredentials }
            .groupBy { Canonicalization.totpFingerprint(it) }
            .mapValues { (_, list) -> list.sortedBy { it.stableId }.first() }

        val destSetByStableId = destination.accounts
            .flatMap { it.recoveryCodeSets }
            .associateBy { it.stableId }

        val destSetByFingerprint = destination.accounts
            .flatMap { it.recoveryCodeSets }
            .groupBy { Canonicalization.recoverySetFingerprint(it.title, it.codes) }
            .mapValues { (_, list) -> list.sortedBy { it.stableId }.first() }

        val destAccountByStableId = destination.accounts.associateBy { it.stableId }
        val destAccountByFingerprint = destination.accounts
            .groupBy { Canonicalization.accountFingerprint(it.serviceName, it.accountName) }
            .mapValues { (_, list) -> list.sortedBy { it.stableId }.first() }

        // ---- planned new accounts (dedupe by fingerprint within the plan) --
        // Maps fingerprint -> the stableId of the account we plan to insert.
        val plannedAccountByFingerprint = HashMap<String, String>()

        val accountPlans = mutableListOf<AccountMergePlan>()
        val matchedDestTotp = HashSet<String>()
        val matchedDestSets = HashSet<String>()

        for (account in source.accounts) {
            val accountFp = Canonicalization.accountFingerprint(account.serviceName, account.accountName)

            val totpPlans = account.totpCredentials.map { t ->
                planTotp(t, destTotpByStableId, destTotpByFingerprint).also { p ->
                    p.matchedDestinationStableId?.let { matchedDestTotp.add(it) }
                }
            }
            val setPlans = account.recoveryCodeSets.map { s ->
                planRecoverySet(s, destSetByStableId, destSetByFingerprint).also { p ->
                    p.matchedDestinationStableId?.let { matchedDestSets.add(it) }
                }
            }

            // Resolve the account action.
            val destByStable = destAccountByStableId[account.stableId]
            val destByFp = destAccountByFingerprint[accountFp]
            val existingTarget = destByStable ?: destByFp

            val hasInsert = totpPlans.any { it.decision == MergeDecision.INSERT } ||
                setPlans.any { it.decision == MergeDecision.INSERT }

            val accountAction: AccountAction
            val targetAccountStableId: String?
            if (existingTarget != null) {
                accountAction = AccountAction.USE_EXISTING
                targetAccountStableId = existingTarget.stableId
            } else if (hasInsert) {
                val planned = plannedAccountByFingerprint[accountFp]
                if (planned != null) {
                    accountAction = AccountAction.USE_EXISTING
                    targetAccountStableId = planned
                } else {
                    accountAction = AccountAction.INSERT_ACCOUNT
                    targetAccountStableId = account.stableId
                    plannedAccountByFingerprint[accountFp] = account.stableId
                }
            } else {
                // Nothing to insert and no existing account -> empty duplicate.
                accountAction = AccountAction.DUPLICATE_ACCOUNT
                targetAccountStableId = null
            }

            accountPlans += AccountMergePlan(
                sourceAccountStableId = account.stableId,
                accountAction = accountAction,
                targetAccountStableId = targetAccountStableId,
                totpPlans = totpPlans,
                recoverySetPlans = setPlans,
            )
        }

        // ---- unchanged: destination records not matched by any source -----
        val destTotpIds = destination.accounts.flatMap { it.totpCredentials.map { c -> c.stableId } }.toSet()
        val destSetIds = destination.accounts.flatMap { it.recoveryCodeSets.map { s -> s.stableId } }.toSet()
        val unchangedTotp = (destTotpIds - matchedDestTotp).size
        val unchangedSets = (destSetIds - matchedDestSets).size

        val summary = MergeSummary(
            inserted = accountPlans.sumOf { p ->
                p.totpPlans.count { it.decision == MergeDecision.INSERT } +
                    p.recoverySetPlans.count { it.decision == MergeDecision.INSERT }
            },
            duplicates = accountPlans.sumOf { p ->
                p.totpPlans.count { it.decision == MergeDecision.DUPLICATE } +
                    p.recoverySetPlans.count { it.decision == MergeDecision.DUPLICATE }
            },
            conflicts = accountPlans.sumOf { p ->
                p.totpPlans.count { it.decision == MergeDecision.CONFLICT } +
                    p.recoverySetPlans.count { it.decision == MergeDecision.CONFLICT }
            },
            unchanged = unchangedTotp + unchangedSets,
            rejected = 0,
        )

        return MergePlan(
            logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
            accountPlans = accountPlans,
            summary = summary,
        )
    }

    private fun planTotp(
        t: VaultTotpCredential,
        byStableId: Map<String, VaultTotpCredential>,
        byFingerprint: Map<String, VaultTotpCredential>,
    ): TotpMergePlan {
        val stableMatch = byStableId[t.stableId]
        if (stableMatch != null) {
            val same =
                Canonicalization.totpFingerprint(t) == Canonicalization.totpFingerprint(stableMatch)
            return if (same) {
                TotpMergePlan(t.stableId, MergeDecision.DUPLICATE, stableMatch.stableId)
            } else {
                TotpMergePlan(t.stableId, MergeDecision.CONFLICT, stableMatch.stableId)
            }
        }
        val fingerprintMatch = byFingerprint[Canonicalization.totpFingerprint(t)]
        if (fingerprintMatch != null) {
            return TotpMergePlan(t.stableId, MergeDecision.DUPLICATE, fingerprintMatch.stableId)
        }
        return TotpMergePlan(t.stableId, MergeDecision.INSERT, null)
    }

    private fun planRecoverySet(
        s: VaultRecoveryCodeSet,
        byStableId: Map<String, VaultRecoveryCodeSet>,
        byFingerprint: Map<String, VaultRecoveryCodeSet>,
    ): RecoverySetMergePlan {
        val stableMatch = byStableId[s.stableId]
        if (stableMatch != null) {
            val sameContent = canonicalCodeValues(s) == canonicalCodeValues(stableMatch)
            return if (sameContent) {
                // Metadata (title) may differ; content identical → duplicate,
                // destination values kept.
                RecoverySetMergePlan(s.stableId, MergeDecision.DUPLICATE, stableMatch.stableId)
            } else {
                RecoverySetMergePlan(s.stableId, MergeDecision.CONFLICT, stableMatch.stableId)
            }
        }
        val fingerprintMatch = byFingerprint[
            Canonicalization.recoverySetFingerprint(s.title, s.codes)
        ]
        if (fingerprintMatch != null) {
            return RecoverySetMergePlan(s.stableId, MergeDecision.DUPLICATE, fingerprintMatch.stableId)
        }
        return RecoverySetMergePlan(s.stableId, MergeDecision.INSERT, null)
    }

    /** Canonical code values ordered by sortOrder then value (deterministic). */
    private fun canonicalCodeValues(s: VaultRecoveryCodeSet): List<String> =
        s.codes.sortedWith(compareBy({ it.sortOrder }, { it.value })).map { it.value }
}

// ---------------------------------------------------------------------------
// Merge plan / result domain model
// ---------------------------------------------------------------------------

enum class MergeDecision { INSERT, DUPLICATE, CONFLICT }

enum class AccountAction { INSERT_ACCOUNT, USE_EXISTING, DUPLICATE_ACCOUNT }

/**
 * Machine-readable merge plan. Phase 3C executes it inside a Room transaction;
 * Phase 3D shows it as the import preview.
 */
data class MergePlan(
    val logicalSchemaVersion: Int,
    val accountPlans: List<AccountMergePlan>,
    val summary: MergeSummary,
)

data class AccountMergePlan(
    val sourceAccountStableId: String,
    val accountAction: AccountAction,
    /** Destination account the inserted children will be attached to. */
    val targetAccountStableId: String?,
    val totpPlans: List<TotpMergePlan>,
    val recoverySetPlans: List<RecoverySetMergePlan>,
)

data class TotpMergePlan(
    val sourceStableId: String,
    val decision: MergeDecision,
    /** Destination record matched by stableId or fingerprint (if any). */
    val matchedDestinationStableId: String?,
)

data class RecoverySetMergePlan(
    val sourceStableId: String,
    val decision: MergeDecision,
    val matchedDestinationStableId: String?,
)

/**
 * Structured merge result (counts). `inserted` + `duplicates` + `conflicts`
 * cover every source record; `unchanged` counts destination-only records that
 * are never deleted. `rejected` is always 0 for a validated package (the whole
 * package is rejected on validation failure — never a partial plan).
 */
data class MergeSummary(
    val inserted: Int,
    val duplicates: Int,
    val conflicts: Int,
    val unchanged: Int,
    val rejected: Int = 0,
)
