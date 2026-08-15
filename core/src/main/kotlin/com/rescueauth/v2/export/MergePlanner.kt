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
 * - Developer Entries (all five types) are matched by stableId only. The
 *   canonical FULL LOGICAL PAYLOAD fingerprint is used to tell DUPLICATE
 *   (full logical payload identical) from CONFLICT (any user-meaningful
 *   field differs — including title / notes / projectName / packageName /
 *   serviceName / accountName / keyName / env variable names / generic
 *   field labels, not just the sensitive payload) for a same-stableId
 *   lineage, but it is NEVER used to dedupe across different stableIds —
 *   identical sensitive payloads do not prove the same logical asset, so
 *   different stableIds are always INSERT / keep both (ROADMAP §8.3).
 *
 * ## Decisions
 *
 * - INSERT: source record does not exist in destination → it will be added
 *   (account inserted only if it must carry at least one INSERT).
 * - DUPLICATE: destination already holds an identical record (same stableId
 *   and same content, or — for TOTP / recovery sets — different stableId but
 *   same semantic fingerprint) → skipped. For Developer Entries the content
 *   comparison is the canonical FULL LOGICAL PAYLOAD (every user-meaningful
 *   field; see [Canonicalization.developerLogicalFingerprint]); a
 *   different stableId is NEVER a DUPLICATE (keep both).
 * - CONFLICT: same stableId (same lineage) but security-sensitive content
 *   (secret / TOTP params / recovery-code values / developer FULL LOGICAL
 *   PAYLOAD) differs → reported, never last-write-wins, never silently
 *   overwritten.
 *
 * ## Recovery used/unused divergence (user state, never silently dropped)
 *
 * The recovery-set fingerprint excludes `status`/`usedAt` so that marking a
 * code used on one device does not turn a later import into a spurious
 * "conflict". However, a used/unused divergence is REAL user state and must
 * never be treated as pure metadata to be silently discarded:
 *
 * - destination = UNUSED, source = USED  → the source user consumed this code.
 *   This is a user-state divergence: the plan records it explicitly
 *   ([RecoveryCodeMergePlan.stateDivergence]) so the import UI / Phase 3C
 *   surfaces it (default: keep destination, report the divergence). The
 *   planner never silently keeps destination while pretending nothing
 *   happened, and never silently overwrites destination with the source state.
 * - destination = USED, source = UNUSED → cannot "un-use" a code; the plan
 *   keeps the destination USED state and reports the divergence.
 *
 * Both cases are surfaced in [RecoveryCodeMergePlan.stateDivergence] and
 * counted in [MergeSummary.stateDivergences] so no source-state is silently
 * lost.
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

        val destDeveloperByStableId = destination.developerEntries.associateBy { it.stableId }

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

            // P8 §24: an explicitly-created empty Account (no TOTP, no Recovery
            // Set) is itself a logical object. When it is absent from the
            // destination, the container must still produce INSERT_ACCOUNT so
            // the account survives export→import even though it carries no
            // children (never silently collapsed into a no-op selection).
            val isEmptyAccount = account.totpCredentials.isEmpty() && account.recoveryCodeSets.isEmpty()

            val accountAction: AccountAction
            val targetAccountStableId: String?
            if (existingTarget != null) {
                accountAction = AccountAction.USE_EXISTING
                targetAccountStableId = existingTarget.stableId
            } else if (hasInsert || isEmptyAccount) {
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
                // Nothing to insert and no existing account -> empty duplicate
                // (only reachable for a NON-empty account whose children are all
                // semantic duplicates matched to existing destination accounts;
                // an empty account is always a distinct container).
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

        // ---- Developer Entries (conservative merge semantics) ------------
        // Only stableId participates in developer dedupe (same FULL LOGICAL
        // PAYLOAD → DUPLICATE, any user-meaningful field different →
        // CONFLICT). Different stableIds are always INSERT / keep both:
        // identical sensitive payloads across stableIds are NOT proof of the
        // same logical asset.
        val developerPlans = source.developerEntries.map { entry ->
            planDeveloperEntry(entry, destDeveloperByStableId)
        }

        // ---- unchanged: destination records not matched by any source -----
        val destTotpIds = destination.accounts.flatMap { it.totpCredentials.map { c -> c.stableId } }.toSet()
        val destSetIds = destination.accounts.flatMap { it.recoveryCodeSets.map { s -> s.stableId } }.toSet()
        val destDeveloperIds = destination.developerEntries.map { it.stableId }.toSet()
        val matchedDestDeveloper = developerPlans.mapNotNull { it.matchedDestinationStableId }.toSet()
        val unchangedTotp = (destTotpIds - matchedDestTotp).size
        val unchangedSets = (destSetIds - matchedDestSets).size
        val unchangedDeveloper = (destDeveloperIds - matchedDestDeveloper).size

        val summary = MergeSummary(
            inserted = accountPlans.sumOf { p ->
                p.totpPlans.count { it.decision == MergeDecision.INSERT } +
                    p.recoverySetPlans.count { it.decision == MergeDecision.INSERT }
            } + developerPlans.count { it.decision == MergeDecision.INSERT },
            duplicates = accountPlans.sumOf { p ->
                p.totpPlans.count { it.decision == MergeDecision.DUPLICATE } +
                    p.recoverySetPlans.count { it.decision == MergeDecision.DUPLICATE }
            } + developerPlans.count { it.decision == MergeDecision.DUPLICATE },
            conflicts = accountPlans.sumOf { p ->
                p.totpPlans.count { it.decision == MergeDecision.CONFLICT } +
                    p.recoverySetPlans.count { it.decision == MergeDecision.CONFLICT }
            } + developerPlans.count { it.decision == MergeDecision.CONFLICT },
            unchanged = unchangedTotp + unchangedSets + unchangedDeveloper,
            rejected = 0,
            stateDivergences = accountPlans.sumOf { p ->
                p.recoverySetPlans.sumOf { it.stateDivergences }
            },
        )

        return MergePlan(
            logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
            accountPlans = accountPlans,
            developerPlans = developerPlans,
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
                // destination values kept. Used/unused divergences are still
                // reported as state divergences (never silently dropped).
                RecoverySetMergePlan(
                    sourceStableId = s.stableId,
                    decision = MergeDecision.DUPLICATE,
                    matchedDestinationStableId = stableMatch.stableId,
                    codePlans = planCodeStates(s.codes, stableMatch.codes),
                )
            } else {
                RecoverySetMergePlan(
                    sourceStableId = s.stableId,
                    decision = MergeDecision.CONFLICT,
                    matchedDestinationStableId = stableMatch.stableId,
                    codePlans = planCodeStates(s.codes, stableMatch.codes),
                )
            }
        }
        val fingerprintMatch = byFingerprint[
            Canonicalization.recoverySetFingerprint(s.title, s.codes)
        ]
        if (fingerprintMatch != null) {
            return RecoverySetMergePlan(
                sourceStableId = s.stableId,
                decision = MergeDecision.DUPLICATE,
                matchedDestinationStableId = fingerprintMatch.stableId,
                codePlans = planCodeStates(s.codes, fingerprintMatch.codes),
            )
        }
        return RecoverySetMergePlan(
            sourceStableId = s.stableId,
            decision = MergeDecision.INSERT,
            matchedDestinationStableId = null,
        )
    }

    /**
     * Compares per-code user state (used/unused) between source and the
     * matched destination set. Produces one [RecoveryCodeMergePlan] per code;
     * every used/unused divergence is recorded explicitly so that no
     * source-state is silently lost.
     *
     * Codes are matched by VALUE (the set-level fingerprint already guarantees
     * both sides carry the same code values, even when their stableIds differ
     * because the sets were independently created); stableId is used as a
     * secondary key for same-lineage sets.
     *
     * The set-level decision stays whatever it was; the state divergence is
     * reported as a separate, visible signal.
     */
    private fun planCodeStates(
        sourceCodes: List<VaultRecoveryCode>,
        destCodes: List<VaultRecoveryCode>,
    ): List<RecoveryCodeMergePlan> {
        val destByValue = destCodes.associateBy { Canonicalization.canonicalLabel(it.value) }
        return sourceCodes.sortedWith(compareBy({ it.sortOrder }, { it.value })).map { src ->
            val dest = destByValue[Canonicalization.canonicalLabel(src.value)]
            val divergence = if (dest != null && src.status != dest.status) {
                RecoveryCodeStateDivergence(
                    codeStableId = src.stableId,
                    value = src.value,
                    destinationStatus = dest.status,
                    sourceStatus = src.status,
                    destinationUsedAt = dest.usedAt,
                    sourceUsedAt = src.usedAt,
                )
            } else {
                null
            }
            RecoveryCodeMergePlan(
                codeStableId = src.stableId,
                value = src.value,
                sourceStatus = src.status,
                sourceUsedAt = src.usedAt,
                stateDivergence = divergence,
            )
        }
    }

    /**
     * Conservative Developer Entry planning (ROADMAP §8.3).
     *
     * - same stableId + canonical FULL LOGICAL PAYLOAD identical →
     *   DUPLICATE (only pure technical metadata such as createdAt / updatedAt
     *   may differ; destination values are kept);
     * - same stableId + ANY user-meaningful logical field different (title /
     *   notes / projectName / packageName / serviceName / accountName /
     *   keyName / env variable names / generic field labels — not just the
     *   sensitive payload) → CONFLICT (never last-write-wins, never silently
     *   overwritten, never silently dropped);
     * - different stableId → INSERT / keep both. The logical payload is
     *   intentionally NOT used to dedupe across stableIds: the same secret /
     *   private key / keystore bytes / env values can legitimately serve
     *   different service/account/project/keyName/label semantics, and
     *   silently dropping one of them would lose user data. Full per-type
     *   semantic identity is deferred to a later enhancement.
     */
    private fun planDeveloperEntry(
        entry: VaultDeveloperEntry,
        byStableId: Map<String, VaultDeveloperEntry>,
    ): DeveloperMergePlan {
        val stableMatch = byStableId[entry.stableId]
        if (stableMatch != null) {
            val same = Canonicalization.developerLogicalFingerprint(entry) ==
                Canonicalization.developerLogicalFingerprint(stableMatch)
            return if (same) {
                DeveloperMergePlan(entry.stableId, MergeDecision.DUPLICATE, stableMatch.stableId)
            } else {
                DeveloperMergePlan(entry.stableId, MergeDecision.CONFLICT, stableMatch.stableId)
            }
        }
        return DeveloperMergePlan(entry.stableId, MergeDecision.INSERT, null)
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
    val developerPlans: List<DeveloperMergePlan> = emptyList(),
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
    /**
     * Per-code state comparison (used/unused) against the matched destination
     * set. Empty for an INSERT (no destination to compare to).
     */
    val codePlans: List<RecoveryCodeMergePlan> = emptyList(),
) {
    /** Number of used/unused divergences detected for this set. */
    val stateDivergences: Int get() = codePlans.count { it.stateDivergence != null }
}

/**
 * Per-code merge decision inside a [RecoverySetMergePlan]. The used/unused
 * state is user state: any divergence is surfaced via [stateDivergence] and
 * never silently dropped or silently applied.
 */
data class RecoveryCodeMergePlan(
    val codeStableId: String,
    val value: String,
    val sourceStatus: String,
    val sourceUsedAt: String?,
    /** Non-null when destination and source disagree on used/unused. */
    val stateDivergence: RecoveryCodeStateDivergence? = null,
)

/**
 * Explicit used/unused divergence between destination and source for a single
 * recovery code. Phase 3C/3D must surface this to the user; it is never
 * resolved by silently keeping the destination state.
 */
data class RecoveryCodeStateDivergence(
    val codeStableId: String,
    val value: String,
    val destinationStatus: String,
    val sourceStatus: String,
    val destinationUsedAt: String?,
    val sourceUsedAt: String?,
)

data class DeveloperMergePlan(
    val sourceStableId: String,
    val decision: MergeDecision,
    /** Destination record matched by stableId or fingerprint (if any). */
    val matchedDestinationStableId: String?,
)

/**
 * Structured merge result (counts). `inserted` + `duplicates` + `conflicts`
 * cover every source record; `unchanged` counts destination-only records that
 * are never deleted. `rejected` is always 0 for a validated package (the whole
 * package is rejected on validation failure — never a partial plan).
 * `stateDivergences` counts used/unused recovery-code divergences surfaced by
 * the plan (they are additional to the four base counts).
 */
data class MergeSummary(
    val inserted: Int,
    val duplicates: Int,
    val conflicts: Int,
    val unchanged: Int,
    val rejected: Int = 0,
    val stateDivergences: Int = 0,
)
