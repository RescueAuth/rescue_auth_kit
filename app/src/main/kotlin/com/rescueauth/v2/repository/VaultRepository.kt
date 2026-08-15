package com.rescueauth.v2.repository

import com.rescueauth.v2.database.AuthAccountDao
import com.rescueauth.v2.database.DeveloperEntryDao
import com.rescueauth.v2.database.ImportRecordDao
import com.rescueauth.v2.database.RecoveryCodeDao
import com.rescueauth.v2.database.RecoveryCodeSetDao
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.database.TotpCredentialDao
import com.rescueauth.v2.export.VaultAccount
import com.rescueauth.v2.export.VaultRecoveryCode
import com.rescueauth.v2.export.VaultRecoveryCodeSet
import com.rescueauth.v2.export.VaultSnapshot
import com.rescueauth.v2.export.VaultTotpCredential
import com.rescueauth.v2.session.SecureSessionStateMachine
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single entry point for all database mutations (ADR-0003 §3).
 *
 * - All writes are serialized through [mutex] so concurrent mutations can
 *   never interleave or drop data.
 * - Every mutation checks [session.isUnlocked] first; a locked session
 *   throws [SessionLockedException] instead of touching a closed DB.
 * - **No automatic/background backup hooks live here.** The Phase 3 product
 *   model is manual Export Package only: every change is recorded in the
 *   local encrypted vault, and the user explicitly exports a portable package
 *   when they want a backup / migration (see docs/PACKAGE_FORMAT.md).
 *
 * Read-only queries (observeX) can run concurrently with the mutex held for
 * writes only.
 */
class VaultRepository(
    private val db: RescueAuthDatabase,
    private val session: SecureSessionStateMachine,
) {
    class SessionLockedException(message: String = "Session is locked") : Exception(message)

    private val mutex = Mutex()

    private val accountDao: AuthAccountDao get() = db.authAccountDao()
    private val totpDao: TotpCredentialDao get() = db.totpCredentialDao()
    private val recoverySetDao: RecoveryCodeSetDao get() = db.recoveryCodeSetDao()
    private val recoveryCodeDao: RecoveryCodeDao get() = db.recoveryCodeDao()
    private val importDao: ImportRecordDao get() = db.importRecordDao()
    private val developerDao: DeveloperEntryDao get() = db.developerEntryDao()

    // ------------------------------------------------------------------
    // Reads (no lock required — DB handle is closed on lock so calls fail)
    // ------------------------------------------------------------------

    fun observeAccounts() = accountDao.observeAll()
    fun observeTotp(accountId: String) = totpDao.observeByAccount(accountId)
    fun observeRecoverySets(accountId: String) = recoverySetDao.observeByAccount(accountId)

    /**
     * Phase 3C: builds the logical destination snapshot of the CURRENT local
     * Vault (Authenticator + Developer sections) for merge planning.
     *
     * Pure mapping — no writes, no random, no clocks. Used by
     * [applyMergePlan] to compute the plan; also exposed so a Phase 3D
     * preview can compute a plan without applying it.
     */
    suspend fun buildDestinationSnapshot(): VaultSnapshot {
        val accounts = accountDao.listAll().map { account ->
            val totps = totpDao.listByAccount(account.id).map { t ->
                VaultTotpCredential(
                    stableId = t.stableId,
                    secretBase32 = t.secretBase32,
                    algorithm = t.algorithm,
                    digits = t.digits,
                    periodSeconds = t.periodSeconds,
                    createdAt = t.createdAt,
                )
            }
            val sets = recoverySetDao.listByAccount(account.id).map { set ->
                VaultRecoveryCodeSet(
                    stableId = set.stableId,
                    title = set.title,
                    createdAt = set.createdAt,
                    codes = recoveryCodeDao.listBySet(set.id).map { c ->
                        VaultRecoveryCode(
                            stableId = c.stableId,
                            value = c.value,
                            status = c.status,
                            usedAt = c.usedAt,
                            sortOrder = c.sortOrder,
                        )
                    },
                )
            }
            VaultAccount(
                stableId = account.stableId,
                serviceName = account.serviceName,
                accountName = account.accountName,
                favorite = account.favorite,
                notes = account.notes,
                sortOrder = account.sortOrder,
                createdAt = account.createdAt,
                updatedAt = account.updatedAt,
                totpCredentials = totps,
                recoveryCodeSets = sets,
            )
        }
        val developerEntries = developerDao.listAll().map { DeveloperMappers.toLogical(it) }
        return VaultSnapshot(
            accounts = accounts,
            developerEntries = developerEntries,
            scope = com.rescueauth.v2.export.SnapshotScope.FULL_VAULT,
        )
    }

    /**
     * Phase 3D: builds a **consistent** logical snapshot of the CURRENT local
     * Vault for Full Vault Export (Issue #1 §17).
     *
     * Same serialized boundary as [buildDestinationSnapshot] (single mutex + a
     * single Room transaction) so an export never observes an interleaved
     * mutation — it cannot read Provider A, then have the user delete Account B
     * mid-snapshot, then read a stale/missing child.
     *
     * Unlike [buildDestinationSnapshot] (a merge internal helper), this is the
     * dedicated **export** boundary. It does not mint new identities and does
     * not write anything; it returns a snapshot whose records carry the real
     * stableIds, exactly as they will be re-imported on another device.
     */
    suspend fun buildConsistentExportSnapshot(): VaultSnapshot {
        checkUnlocked()
        return mutex.withLock {
            db.withTransaction {
                buildDestinationSnapshot()
            }
        }
    }

    /**
     * Phase 3C: transactional import/merge entry point.
     *
     * ```
     * VaultPackagePayload → PackageValidator → MergePlanner → this
     * (transactional apply) → local Vault
     * ```
     *
     * - validates [payload] (logical consistency) before any plan is built;
     * - builds the plan against the CURRENT local Vault snapshot;
     * - applies the plan inside ONE serialized Room transaction
     *   ([MergePlanApplicator]);
     * - any mid-apply failure rolls back EVERYTHING (incl. the ImportRecord);
     * - a blocked plan (CONFLICT / recovery state divergence) is reported via
     *   [ImportBlockedResult] and writes NOTHING.
     *
     * The method is idempotent for a repeated package: the second plan is all
     * DUPLICATE and the apply inserts nothing (stableId / merge semantics are
     * the real idempotence source, not the ImportRecord).
     */
    suspend fun applyMergePlan(
        payload: com.rescueauth.v2.export.VaultPackagePayload,
        seam: WriteSeam = WriteSeam.None,
    ): ImportOutcome {
        checkUnlocked()
        com.rescueauth.v2.export.PackageValidator.validate(payload)
        return mutex.withLock {
            db.withTransaction {
                val destination = buildDestinationSnapshot()
                val plan = com.rescueauth.v2.export.MergePlanner.plan(destination, payload.snapshot)
                val blocked = MergePlanApplicator(
                    accountDao = accountDao,
                    totpDao = totpDao,
                    recoverySetDao = recoverySetDao,
                    recoveryCodeDao = recoveryCodeDao,
                    developerDao = developerDao,
                    importDao = importDao,
                    seam = seam,
                ).preflight(plan)
                if (blocked != null) {
                    return@withTransaction ImportOutcome.Blocked(
                        ImportBlockedResult(
                            conflicts = plan.summary.conflicts,
                            stateDivergences = plan.summary.stateDivergences,
                            duplicates = plan.summary.duplicates,
                            unchanged = plan.summary.unchanged,
                        )
                    )
                }
                val result = MergePlanApplicator(
                    accountDao = accountDao,
                    totpDao = totpDao,
                    recoverySetDao = recoverySetDao,
                    recoveryCodeDao = recoveryCodeDao,
                    developerDao = developerDao,
                    importDao = importDao,
                    seam = seam,
                ).apply(payload.snapshot, plan, packageIdentity = payload.packageId)
                ImportOutcome.Applied(result)
            }
        }
    }

    /**
     * Same as [applyMergePlan] but takes an already-validated logical snapshot
     * instead of a full [com.rescueauth.v2.export.VaultPackagePayload]. This
     * is the shared boundary that future legacy / otpauth-migration adapters
     * funnel through (ROADMAP §9): an external adapter parses its source into
     * a logical snapshot, then reuses this exact transactional merge path.
     */
    suspend fun applySnapshot(
        snapshot: VaultSnapshot,
        packageIdentity: String? = null,
        seam: WriteSeam = WriteSeam.None,
        sourceType: String = "V2_PACKAGE",
    ): ImportOutcome {
        checkUnlocked()
        com.rescueauth.v2.export.PackageValidator.validate(snapshot)
        return mutex.withLock {
            db.withTransaction {
                val destination = buildDestinationSnapshot()
                val plan = com.rescueauth.v2.export.MergePlanner.plan(destination, snapshot)
                val blocked = MergePlanApplicator(
                    accountDao = accountDao,
                    totpDao = totpDao,
                    recoverySetDao = recoverySetDao,
                    recoveryCodeDao = recoveryCodeDao,
                    developerDao = developerDao,
                    importDao = importDao,
                    seam = seam,
                ).preflight(plan)
                if (blocked != null) {
                    return@withTransaction ImportOutcome.Blocked(
                        ImportBlockedResult(
                            conflicts = plan.summary.conflicts,
                            stateDivergences = plan.summary.stateDivergences,
                            duplicates = plan.summary.duplicates,
                            unchanged = plan.summary.unchanged,
                        )
                    )
                }
                val result = MergePlanApplicator(
                    accountDao = accountDao,
                    totpDao = totpDao,
                    recoverySetDao = recoverySetDao,
                    recoveryCodeDao = recoveryCodeDao,
                    developerDao = developerDao,
                    importDao = importDao,
                    seam = seam,
                ).apply(snapshot, plan, packageIdentity, sourceType)
                ImportOutcome.Applied(result)
            }
        }
    }

    // ------------------------------------------------------------------
    // Mutations (serialized)
    // ------------------------------------------------------------------


    /** Marks a recovery code as used (serialized). */
    suspend fun markRecoveryCodeUsed(codeId: String, usedAt: String = java.time.Instant.now().toString()) {
        checkUnlocked()
        mutex.withLock {
            db.withTransaction {
                recoveryCodeDao.markUsed(codeId, usedAt)
            }
        }
    }

    /** Marks a recovery code as unused again. */
    suspend fun markRecoveryCodeUnused(codeId: String) {
        checkUnlocked()
        mutex.withLock {
            db.withTransaction {
                recoveryCodeDao.markUnused(codeId)
            }
        }
    }

    /** Toggles an account's favorite flag (internal persisted compatibility field). */
    suspend fun setFavorite(accountId: String, favorite: Boolean) {
        checkUnlocked()
        mutex.withLock {
            db.withTransaction {
                accountDao.setFavorite(accountId, favorite, java.time.Instant.now().toString())
            }
        }
    }

    /**
     * P7 product-facing alias for Account Pin/Unpin.
     *
     * Reuses the existing `favorite` storage column as the persisted pin state
     * (no Room/package schema migration, P7 §17). Pin (`true`) / Unpin
     * (`false`) is a serialized mutation that checks the session and updates
     * `updatedAt` per the existing contract. `favorite` is retained only as an
     * internal compatibility field; the product/UI exposes Pin/Unpin only.
     */
    suspend fun setPinned(accountId: String, pinned: Boolean) {
        setFavorite(accountId, pinned)
    }

    suspend fun deleteAccount(accountId: String) {
        checkUnlocked()
        mutex.withLock {
            db.withTransaction {
                accountDao.deleteById(accountId)
            }
        }
    }

    /**
     * Runs [block] as one serialized, transactional mutation. All production
     * Authenticator CRUD funnels through here so concurrency and session-lock
     * guarantees stay in a single place.
     */
    suspend fun <T> mutate(block: suspend () -> T): T {
        checkUnlocked()
        return mutex.withLock {
            db.withTransaction {
                block()
            }
        }
    }

    private fun checkUnlocked() {
        if (!session.isUnlocked()) throw SessionLockedException()
    }
}

/**
 * Outcome of a Phase 3C merge apply.
 *
 * - [Applied]: the plan was applied inside the transaction (inserts + records);
 * - [Blocked]: the plan was NOT applied — it contains unresolved CONFLICTs or
 *   recovery used/unused state divergences that must be surfaced to the user
 *   (Phase 3D). Nothing was written.
 */
sealed interface ImportOutcome {
    data class Applied(val result: ApplyResult) : ImportOutcome
    data class Blocked(val result: ImportBlockedResult) : ImportOutcome
}

/** Reason a merge apply was blocked (Phase 3C conservative policy). */
data class ImportBlockedResult(
    val conflicts: Int,
    val stateDivergences: Int,
    val duplicates: Int,
    val unchanged: Int,
) {
    val blocked: Boolean get() = true
}
