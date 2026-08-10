package com.rescueauth.v2.repository

import com.rescueauth.v2.database.AuthAccountDao
import com.rescueauth.v2.database.AuthAccountEntity
import com.rescueauth.v2.database.RecoveryCodeDao
import com.rescueauth.v2.database.RecoveryCodeSetDao
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.database.TotpCredentialDao
import com.rescueauth.v2.domain.AuthAccount
import com.rescueauth.v2.domain.DeletedAccountSnapshot
import com.rescueauth.v2.domain.TotpCredential
import com.rescueauth.v2.domain.UndoRestoreOutcome
import com.rescueauth.v2.export.Canonicalization
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Provider & Account full management (Phase 4 — Provider/Account hierarchy).
 *
 * Implements the formal Provider (create / rename / delete) and Account
 * (create / rename / move / merge / delete) operations on top of the existing
 * persistence model:
 *
 * - **Provider has no Room entity and no package stableId.** A Provider is
 *   the `serviceName` grouping of accounts in the portable logical schema
 *   (ROADMAP §8 / PACKAGE_FORMAT §Identity). Provider identity is therefore
 *   the `serviceName` string itself — NOT a DB row id and NOT a package
 *   stableId. Renaming a Provider is an update of the `serviceName` column on
 *   every descendant Account (their `stableId`s are untouched).
 * - **Empty Provider**: the current persistence model does NOT support an
 *   empty Provider. An Account row always carries its `serviceName`, and
 *   creating a Provider therefore requires creating its first Account. This
 *   is the honest reflection of the current model; we do NOT redesign the
 *   schema to add an empty-Provider table (out of scope this round).
 * - All mutations funnel through the shared [VaultRepository] serialized
 *   mutex + a single Room transaction, so cross-table mutations (Account
 *   move / merge / Provider delete) are atomic.
 *
 * ## Security
 *
 * Management operations move/relink entities by FK only. They never read or
 * expose TOTP secrets or recovery-code values to the UI. Counts (TOTP /
 * Recovery Set) are computed for confirmation dialogs but secrets never
 * leave the repository.
 */
class ProviderAccountRepository(
    private val vault: VaultRepository,
    private val db: RescueAuthDatabase,
    @Suppress("unused") private val session: SecureSessionStateMachine,
) {
    class ValidationException(message: String) : Exception(message)
    class NotFoundException(message: String) : Exception(message)
    class ConflictException(message: String) : Exception(message)

    private val accountDao: AuthAccountDao get() = db.authAccountDao()
    private val totpDao: TotpCredentialDao get() = db.totpCredentialDao()
    private val recoverySetDao: RecoveryCodeSetDao get() = db.recoveryCodeSetDao()
    private val recoveryCodeDao: RecoveryCodeDao get() = db.recoveryCodeDao()

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** All distinct provider (serviceName) names, alphabetically sorted. */
    suspend fun listProviders(): List<String> =
        accountDao.listAll().map { it.serviceName }.distinct().sorted()

    fun observeAccounts(): Flow<List<AuthAccount>> =
        accountDao.observeAll().map { list -> list.map { AuthMappers.toDomain(it) } }

    /** TOTP credentials belonging to [accountId] (used for counts / merge). */
    suspend fun listTotpByAccount(accountId: String): List<TotpCredential> =
        totpDao.listByAccount(accountId).map { AuthMappers.toDomain(it) }

    // ------------------------------------------------------------------
    // Provider operations
    // ------------------------------------------------------------------

    /**
     * Creates a Provider (serviceName) and its first Account.
     *
     * The current model does not support an empty Provider — an Account row
     * is the smallest persistable unit and always carries its `serviceName`.
     * Therefore "Create Provider" persists the Provider via its first Account.
     * [accountName] is required.
     *
     * Exact-duplicate provider name: rejected with [ConflictException] if any
     * Account with the same `serviceName` already exists. Never silently
     * reused — this is an explicit management create action.
     */
    suspend fun createProvider(serviceName: String, accountName: String): AuthAccount {
        val provider = normalizeProviderName(serviceName)
        if (provider.isEmpty()) throw ValidationException("Provider name is required")
        val account = normalizeAccountName(accountName)
        if (account.isEmpty()) throw ValidationException("Account name is required")

        return vault.mutate {
            val existing = accountDao.findByServiceAndAccount(provider, account)
            // Block when the provider name itself already exists (any account).
            if (accountDao.countByServiceName(provider) > 0) {
                throw ConflictException("Provider already exists: $provider")
            }
            val now = java.time.Instant.now().toString()
            val id = UUID.randomUUID().toString()
            val entity = AuthAccountEntity(
                id = id,
                stableId = UUID.randomUUID().toString(),
                serviceName = provider,
                accountName = account,
                favorite = false,
                notes = null,
                sortOrder = System.currentTimeMillis(),
                createdAt = now,
                updatedAt = now,
            )
            accountDao.upsert(entity)
            AuthMappers.toDomain(entity)
        }
    }

    /**
     * Creates an Account under an existing Provider.
     *
     * The Provider must already exist (has at least one Account with that
     * serviceName). An exact-duplicate (provider, accountName) is rejected
     * with [ConflictException] — never silently merged.
     */
    suspend fun createAccount(serviceName: String, accountName: String): AuthAccount {
        val provider = normalizeProviderName(serviceName)
        if (provider.isEmpty()) throw ValidationException("Provider name is required")
        val account = normalizeAccountName(accountName)
        if (account.isEmpty()) throw ValidationException("Account name is required")

        return vault.mutate {
            if (accountDao.countByServiceName(provider) == 0) {
                throw NotFoundException("Provider not found: $provider")
            }
            if (accountDao.findByServiceAndAccount(provider, account) != null) {
                throw ConflictException("Account already exists: $provider / $account")
            }
            val now = java.time.Instant.now().toString()
            val id = UUID.randomUUID().toString()
            val entity = AuthAccountEntity(
                id = id,
                stableId = UUID.randomUUID().toString(),
                serviceName = provider,
                accountName = account,
                favorite = false,
                notes = null,
                sortOrder = System.currentTimeMillis(),
                createdAt = now,
                updatedAt = now,
            )
            accountDao.upsert(entity)
            AuthMappers.toDomain(entity)
        }
    }

    /**
     * Renames a Provider (serviceName) — updates `serviceName` on every
     * descendant Account. All descendant stableIds (Account / TOTP / Recovery
     * Set / Recovery Code) and all USED/usedAt states are preserved.
     *
     * This is a pure hierarchy-metadata update: no TOTP secret is touched, no
     * otpauth re-parse, no credential stableId rebuild.
     *
     * Rename-to-existing-provider is rejected with [ConflictException] — a
     * rename is NEVER turned into a destructive merge.
     */
    suspend fun renameProvider(oldServiceName: String, newServiceName: String): String {
        val oldName = normalizeProviderName(oldServiceName)
        val newName = normalizeProviderName(newServiceName)
        if (oldName.isEmpty()) throw ValidationException("Provider name is required")
        if (newName.isEmpty()) throw ValidationException("New provider name is required")
        if (oldName == newName) return newName // no-op

        return vault.mutate {
            if (accountDao.countByServiceName(oldName) == 0) {
                throw NotFoundException("Provider not found: $oldName")
            }
            if (accountDao.countByServiceName(newName) > 0) {
                throw ConflictException("Provider already exists: $newName")
            }
            val now = java.time.Instant.now().toString()
            accountDao.updateServiceNameForAll(oldName, newName, now)
            newName
        }
    }

    /**
     * Deletes a Provider (serviceName) and every descendant Account, TOTP
     * credential and Recovery Code Set/Codes in a single transaction.
     *
     * Returns counts of what was deleted (for the confirmation UI). Orphaned
     * children are impossible because accounts are deleted by serviceName and
     * their children cascade via Room FK (TOTP / Recovery Set → Account) and
     * the explicit Recovery Code cleanup. A mid-transaction failure rolls the
     * whole operation back.
     */
    suspend fun deleteProvider(serviceName: String): ProviderDeleteResult {
        val provider = normalizeProviderName(serviceName)
        if (provider.isEmpty()) throw ValidationException("Provider name is required")

        return vault.mutate {
            val accounts = accountDao.listByServiceName(provider)
            if (accounts.isEmpty()) throw NotFoundException("Provider not found: $provider")
            var totpCount = 0
            var recoverySetCount = 0
            for (a in accounts) {
                totpCount += totpDao.countByAccount(a.id)
                recoverySetCount += recoverySetDao.listByAccount(a.id).size
                // Explicit code delete before set delete is safe (FK CASCADE
                // would also handle it, but being explicit keeps the intent
                // clear and is robust to any FK policy).
                val sets = recoverySetDao.listByAccount(a.id)
                for (s in sets) {
                    recoveryCodeDao.listBySet(s.id).forEach { recoveryCodeDao.deleteById(it.id) }
                    recoverySetDao.deleteById(s.id)
                }
                totpDao.listByAccount(a.id).forEach { totpDao.deleteById(it.id) }
                accountDao.deleteById(a.id)
            }
            ProviderDeleteResult(accounts.size, totpCount, recoverySetCount)
        }
    }

    // ------------------------------------------------------------------
    // Account operations
    // ------------------------------------------------------------------

    /**
     * Renames an Account — preserves its stableId and all children (TOTP /
     * Recovery Set / Code stableIds and states). Only the `accountName`
     * column (plus `updatedAt`) is updated. Exact-duplicate account name
     * within the provider is rejected with [ConflictException].
     */
    suspend fun renameAccount(accountId: String, newAccountName: String): AuthAccount {
        val newName = normalizeAccountName(newAccountName)
        if (newName.isEmpty()) throw ValidationException("Account name is required")

        return vault.mutate {
            val row = accountDao.getById(accountId)
                ?: throw NotFoundException("Account not found")
            if (row.accountName == newName) return@mutate AuthMappers.toDomain(row)
            val dup = accountDao.findByServiceAndAccount(row.serviceName, newName)
            if (dup != null) {
                throw ConflictException("Account already exists: ${row.serviceName} / $newName")
            }
            val now = java.time.Instant.now().toString()
            accountDao.updateAccountName(accountId, newName, now)
            val updated = accountDao.getById(accountId)!!
            AuthMappers.toDomain(updated)
        }
    }

    /**
     * Moves an Account (and its whole hierarchy) to a destination Provider.
     *
     * Account stableId, TOTP stableIds, Recovery Set/Code stableIds and all
     * USED/usedAt states are preserved. Only the `serviceName` parent relation
     * is updated. Moving to the same provider is a safe no-op (returns the
     * account unchanged). Destination provider must already exist.
     */
    suspend fun moveAccount(accountId: String, destinationProvider: String): AuthAccount {
        val destProvider = normalizeProviderName(destinationProvider)
        if (destProvider.isEmpty()) throw ValidationException("Destination provider is required")

        return vault.mutate {
            val row = accountDao.getById(accountId)
                ?: throw NotFoundException("Account not found")
            if (row.serviceName == destProvider) return@mutate AuthMappers.toDomain(row) // no-op
            if (accountDao.countByServiceName(destProvider) == 0) {
                throw NotFoundException("Destination provider not found: $destProvider")
            }
            val dup = accountDao.findByServiceAndAccount(destProvider, row.accountName)
            if (dup != null) {
                throw ConflictException(
                    "Account already exists in destination provider: $destProvider / ${row.accountName}",
                )
            }
            val now = java.time.Instant.now().toString()
            accountDao.updateServiceName(accountId, destProvider, now)
            val updated = accountDao.getById(accountId)!!
            AuthMappers.toDomain(updated)
        }
    }

    /**
     * Merges [sourceAccountId] into [destinationAccountId].
     *
     * ## Destination survives
     *
     * - Destination Account stableId is preserved.
     * - Source Account is deleted after a successful merge.
     *
     * ## TOTP (source → destination)
     *
     * - Non-duplicate source TOTPs move preserving their stableId.
     * - Duplicate source TOTPs (same semantic fingerprint: secret + algorithm
     *   + digits + period) collapse into the destination's existing TOTP —
     *   destination survives, source duplicate is dropped. Duplicate detection
     *   reuses [Canonicalization.totpFingerprint] (the existing official
     *   semantic fingerprint). No second dedupe rule is written.
     *
     * ## Recovery Code Sets
     *
     * - ALL source sets move to the destination, preserving set stableId,
     *   child code stableIds, values, USED/UNUSED and usedAt. Recovery Set
     *   title is NOT a global identity — same-titled sets are both preserved
     *   (never auto-deduped by title or code count).
     *
     * ## Cross-provider
     *
     * - Source and destination may belong to different Providers. After the
     *   merge all children belong to the Destination's Provider.
     * - If the source Provider becomes empty, it is simply left empty — the
     *   current model keeps no explicit empty-Provider row (there is nothing
     *   to delete). Provider deletion is a separate explicit destructive
     *   action and is NOT auto-triggered.
     *
     * ## Transaction
     *
     * The entire merge (move children, delete source) runs in one serialized
     * Room transaction. Any failure rolls back everything.
     *
     * @return a [MergeSummary] of what happened.
     */
    suspend fun mergeAccounts(sourceAccountId: String, destinationAccountId: String): AccountMergeSummary {
        if (sourceAccountId == destinationAccountId) {
            throw ValidationException("Source and destination must be different accounts")
        }
        return vault.mutate {
            val source = accountDao.getById(sourceAccountId)
                ?: throw NotFoundException("Source account not found")
            val destination = accountDao.getById(destinationAccountId)
                ?: throw NotFoundException("Destination account not found")

            // ---- TOTP: move non-duplicates, collapse duplicates ----------
            val sourceTotps = totpDao.listByAccount(source.id)
            val destTotps = totpDao.listByAccount(destination.id)
            val destFingerprints = destTotps.mapTo(HashSet()) {
                Canonicalization.totpFingerprint(it.secretBase32, it.algorithm, it.digits, it.periodSeconds)
            }
            var movedTotp = 0
            var collapsedTotp = 0
            for (t in sourceTotps) {
                val fp = Canonicalization.totpFingerprint(
                    t.secretBase32, t.algorithm, t.digits, t.periodSeconds,
                )
                if (fp in destFingerprints) {
                    // Duplicate: destination existing TOTP survives; drop source.
                    totpDao.deleteById(t.id)
                    collapsedTotp++
                } else {
                    totpDao.updateAccountId(t.id, destination.id)
                    destFingerprints += fp
                    movedTotp++
                }
            }

            // ---- Recovery Sets: move ALL, preserving lineage -------------
            val sourceSets = recoverySetDao.listByAccount(source.id)
            for (s in sourceSets) {
                // Move the set; codes stay attached by setId (FK unchanged).
                recoverySetDao.updateAccountId(s.id, destination.id)
            }

            // ---- Delete source Account (its remaining rows are gone) ------
            accountDao.deleteById(source.id)

            AccountMergeSummary(
                sourceAccountId = source.id,
                destinationAccountId = destination.id,
                movedTotp = movedTotp,
                collapsedTotp = collapsedTotp,
                movedRecoverySets = sourceSets.size,
            )
        }
    }

    /**
     * Deletes an Account and its whole hierarchy (TOTP + Recovery Sets/Codes)
     * in a single transaction. Returns counts for the confirmation UI.
     */
    suspend fun deleteAccount(accountId: String): AccountDeleteResult {
        return vault.mutate {
            val row = accountDao.getById(accountId)
                ?: throw NotFoundException("Account not found")
            val totpCount = totpDao.countByAccount(row.id)
            val sets = recoverySetDao.listByAccount(row.id)
            for (s in sets) {
                recoveryCodeDao.listBySet(s.id).forEach { recoveryCodeDao.deleteById(it.id) }
                recoverySetDao.deleteById(s.id)
            }
            totpDao.listByAccount(row.id).forEach { totpDao.deleteById(it.id) }
            accountDao.deleteById(row.id)
            AccountDeleteResult(totpCount, sets.size)
        }
    }

    // ------------------------------------------------------------------
    // P8 — Account Delete + Undo (Issue #20 P8 §8–§11)
    // ------------------------------------------------------------------

    /**
     * Deletes an Account and its ENTIRE subtree (TOTP + Recovery Sets/Codes)
     * atomically, returning an exact [DeletedAccountSnapshot] captured INSIDE
     * the same transaction so Undo is a faithful rollback of the delete.
     *
     * The snapshot carries every Account field (stableId / serviceName /
     * accountName / pinned(favorite) / notes / sortOrder / createdAt /
     * updatedAt) plus all descendants with their exact stableIds, TOTP
     * secret/params, and Recovery Set/Code stableIds + USED/UNUSED + usedAt
     * (P8 §8). The snapshot is **in-memory only** (P8 §5).
     */
    suspend fun deleteAccountWithSnapshot(accountId: String): DeletedAccountSnapshot? {
        return vault.mutate {
            val row = accountDao.getById(accountId) ?: return@mutate null
            val account = AuthMappers.toDomain(row)
            val totps = totpDao.listByAccount(row.id).map { AuthMappers.toDomain(it) }
            val sets = recoverySetDao.listByAccount(row.id).map { set ->
                AuthMappers.toDomain(set, recoveryCodeDao.listBySet(set.id))
            }

            // Delete codes, then sets, then totps, then the account.
            for (s in sets) {
                recoveryCodeDao.listBySet(s.id).forEach { recoveryCodeDao.deleteById(it.id) }
                recoverySetDao.deleteById(s.id)
            }
            totpDao.listByAccount(row.id).forEach { totpDao.deleteById(it.id) }
            accountDao.deleteById(row.id)

            DeletedAccountSnapshot(account = account, totps = totps, recoverySets = sets)
        }
    }

    /**
     * P8 §9/§10 — restores a previously deleted Account + subtree atomically
     * with EXACT stableIds/states (never re-creates an "equivalent" account and
     * never mints new stableIds).
     *
     * Before writing, it preflights inside the same serialized mutation:
     *
     * - if the same Account stableId already exists with a DIFFERENT payload,
     *   or a TOTP / Recovery Set / Code stableId already exists (schema
     *   uniqueness collision), restore is BLOCKED (P8 §10) — nothing is
     *   written, the caller must clear the pending secret snapshot;
     * - a blocked restore returns [UndoRestoreOutcome.Blocked] — no
     *   source-wins overwrite, no PackageMergePlanner involvement (this is a
     *   local mutation rollback, not a package import).
     */
    suspend fun restoreAccount(snapshot: DeletedAccountSnapshot): UndoRestoreOutcome {
        return vault.mutate {
            // Preflight: reject any stableId already present in the destination
            // (exact restore must not silently overwrite).
            if (accountDao.getByStableId(snapshot.account.stableId) != null) {
                return@mutate UndoRestoreOutcome.Blocked
            }
            val totpStableIds = snapshot.totps.map { it.stableId }.toSet()
            val setStableIds = snapshot.recoverySets.map { it.stableId }.toSet()
            val codeStableIds = snapshot.recoverySets.flatMap { it.codes.map { c -> c.stableId } }.toSet()
            if (totpStableIds.any { totpDao.getByStableId(it) != null }) {
                return@mutate UndoRestoreOutcome.Blocked
            }
            if (setStableIds.any { recoverySetDao.getByStableId(it) != null }) {
                return@mutate UndoRestoreOutcome.Blocked
            }
            if (codeStableIds.any { recoveryCodeDao.getByStableId(it) != null }) {
                return@mutate UndoRestoreOutcome.Blocked
            }

            // Insert account with exact fields.
            accountDao.upsert(
                AuthAccountEntity(
                    id = snapshot.account.id,
                    stableId = snapshot.account.stableId,
                    serviceName = snapshot.account.serviceName,
                    accountName = snapshot.account.accountName,
                    favorite = snapshot.account.favorite,
                    notes = snapshot.account.notes,
                    sortOrder = snapshot.account.sortOrder,
                    createdAt = snapshot.account.createdAt,
                    updatedAt = snapshot.account.updatedAt,
                    legacySourceId = null,
                ),
            )
            // Insert TOTPs with exact stableIds/params.
            snapshot.totps.forEach { t ->
                totpDao.upsert(
                    com.rescueauth.v2.database.TotpCredentialEntity(
                        id = t.id,
                        stableId = t.stableId,
                        accountId = t.accountId,
                        secretBase32 = t.secretBase32,
                        algorithm = t.algorithm,
                        digits = t.digits,
                        periodSeconds = t.periodSeconds,
                        createdAt = t.createdAt,
                        legacySourceId = null,
                    ),
                )
            }
            // Insert Recovery Sets + Codes with exact stableIds/states.
            snapshot.recoverySets.forEach { set ->
                recoverySetDao.upsert(
                    com.rescueauth.v2.database.RecoveryCodeSetEntity(
                        id = set.id,
                        stableId = set.stableId,
                        accountId = set.accountId,
                        title = set.title,
                        createdAt = set.createdAt,
                        legacySourceId = null,
                    ),
                )
                set.codes.sortedBy { it.sortOrder }.forEach { code ->
                    recoveryCodeDao.upsert(
                        com.rescueauth.v2.database.RecoveryCodeEntity(
                            id = code.id,
                            stableId = code.stableId,
                            setId = code.setId,
                            value = code.value,
                            status = if (code.isUsed) "USED" else "UNUSED",
                            usedAt = code.usedAt,
                            sortOrder = code.sortOrder,
                        ),
                    )
                }
            }
            UndoRestoreOutcome.Restored
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun normalizeProviderName(value: String): String = value.trim()

    private fun normalizeAccountName(value: String): String = value.trim()
}

/** Counts returned by [ProviderAccountRepository.deleteProvider]. */
data class ProviderDeleteResult(
    val accountCount: Int,
    val totpCount: Int,
    val recoverySetCount: Int,
)

/** Counts returned by [ProviderAccountRepository.deleteAccount]. */
data class AccountDeleteResult(
    val totpCount: Int,
    val recoverySetCount: Int,
)

/** Summary of an account merge (safe metadata only — no secrets). */
data class AccountMergeSummary(
    val sourceAccountId: String,
    val destinationAccountId: String,
    val movedTotp: Int,
    val collapsedTotp: Int,
    val movedRecoverySets: Int,
)
