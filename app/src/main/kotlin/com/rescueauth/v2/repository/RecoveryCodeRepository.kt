package com.rescueauth.v2.repository

import com.rescueauth.v2.database.RecoveryCodeDao
import com.rescueauth.v2.database.RecoveryCodeEntity
import com.rescueauth.v2.database.RecoveryCodeSetDao
import com.rescueauth.v2.database.RecoveryCodeSetEntity
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.domain.RecoveryCode
import com.rescueauth.v2.domain.RecoveryCodeSet
import com.rescueauth.v2.domain.nextRecoverySetTitle
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID

/**
 * Production Recovery Codes repository (Phase 4 P3).
 *
 * All mutations funnel through the shared [VaultRepository] serialized mutex
 * + Room transaction, so USED/UNUSED changes and set edits are written to the
 * real encrypted Vault immediately (never ViewModel-only), and a Composable
 * never touches a DAO. The repository is deliberately small and keeps the
 * existing AuthenticatorRepository as the account entry point (no ceremony,
 * no second architecture).
 *
 * ## Duplicate policy
 *
 * Deduplication is **exact / canonical whitespace-level only** (opaque secret
 * strings): two lines are duplicates when their trimmed whitespace forms are
 * identical. `ABC-123` vs `ABC123` are distinct — recovery codes are opaque
 * secrets and must never be "smart-normalised". No cross-set global dedupe:
 * the same code may legitimately appear in two different sets.
 *
 * ## Edit / stableId preservation
 *
 * Editing a set performs a minimal diff by **code value** (whitespace-trimmed):
 * unchanged codes keep their original stableId + USED/UNUSED + usedAt; removed
 * codes are deleted; new codes get a fresh stableId / UNUSED. A code whose
 * value itself changed is treated as delete + create (never silently reusing a
 * stableId for a different secret). The whole edit (set metadata + code
 * add/remove/preserve) is one transactional write.
 */
class RecoveryCodeRepository(
    private val vault: VaultRepository,
    private val db: RescueAuthDatabase,
    @Suppress("unused") private val session: SecureSessionStateMachine,
) {
    class ValidationException(message: String) : Exception(message)
    class NotFoundException(message: String) : Exception(message)
    class ConflictException(message: String) : Exception(message)

    private val setDao: RecoveryCodeSetDao get() = db.recoveryCodeSetDao()
    private val codeDao: RecoveryCodeDao get() = db.recoveryCodeDao()

    // ------------------------------------------------------------------
    // Reads (Flow → domain)
    // ------------------------------------------------------------------

    fun observeSetsByAccount(accountId: String): Flow<List<RecoveryCodeSet>> =
        combine(setDao.observeByAccount(accountId), codeDao.observeAll()) { sets, allCodes ->
            val setIds = sets.mapTo(HashSet()) { it.id }
            val codesBySet = allCodes.filter { it.setId in setIds }.groupBy { it.setId }
            sets.map { set ->
                AuthMappers.toDomain(set, codesBySet[set.id].orEmpty())
            }
        }

    /**
     * All recovery sets across all accounts (used by the Authenticator home
     * flow to drive per-account recovery summaries). Secret code values are
     * carried only into the domain layer — the UI model strips them until the
     * user explicitly reveals a code.
     */
    fun observeAllSets(): Flow<List<RecoveryCodeSet>> =
        combine(setDao.observeAll(), codeDao.observeAll()) { setList, allCodes ->
            val codesBySet = allCodes.groupBy { it.setId }
            setList.map { set ->
                AuthMappers.toDomain(set, codesBySet[set.id].orEmpty())
            }
        }

    // ------------------------------------------------------------------
    // Mutations (delegated to the serialized VaultRepository)
    // ------------------------------------------------------------------

    /**
     * Creates a recovery-code set under [accountId] with [title] and the
     * parsed [values]. Duplicate lines are rejected up-front with a clear
     * validation error (exact whitespace-normalised duplicates only).
     */
    suspend fun createSet(accountId: String, title: String, values: List<String>): RecoveryCodeSet {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) throw ValidationException("title is required")
        return persistSet(accountId, values) { trimmedTitle }
    }

    /** Optional UI naming; persisted titles remain non-blank and numbering is account-scoped. */
    suspend fun createSetWithAutomaticTitle(accountId: String, baseTitle: String, values: List<String>): RecoveryCodeSet =
        persistSet(accountId, values) { titles -> nextRecoverySetTitle(baseTitle, titles) }

    private suspend fun persistSet(accountId: String, values: List<String>, title: (List<String>) -> String): RecoveryCodeSet {

        // Leading/trailing whitespace and blank lines are handled here
        // (defensive — the ViewModel already filters them). Codes are opaque
        // secrets: only exact whitespace-normalised duplicates are rejected.
        val normalized = values.map { it.trim() }.filter { it.isNotEmpty() }
        if (normalized.isEmpty()) throw ValidationException("at least one recovery code is required")

        val duplicates = normalized.groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw ValidationException("duplicate recovery code in input")
        }

        val now = java.time.Instant.now().toString()
        val setId = UUID.randomUUID().toString()
        val setStableId = UUID.randomUUID().toString()
        return vault.mutate {
            val resolvedTitle = title(setDao.listByAccount(accountId).map { it.title })
            val set = RecoveryCodeSetEntity(
                id = setId,
                stableId = setStableId,
                accountId = accountId,
                title = resolvedTitle,
                createdAt = now,
            )
            setDao.upsert(set)
            val codes = normalized.mapIndexed { index, value ->
                RecoveryCodeEntity(
                    id = UUID.randomUUID().toString(),
                    stableId = UUID.randomUUID().toString(),
                    setId = setId,
                    value = value,
                    status = "UNUSED",
                    usedAt = null,
                    sortOrder = index,
                )
            }
            codeDao.insertAll(codes)
            AuthMappers.toDomain(set, codes)
        }
    }

    /**
     * Marks a code as USED (persisted immediately through the shared mutex).
     */
    suspend fun markUsed(codeId: String): RecoveryCode? {
        return vault.mutate {
            val code = codeDao.getById(codeId) ?: return@mutate null
            codeDao.markUsed(codeId, java.time.Instant.now().toString())
            AuthMappers.toDomain(code.copy(status = "USED"))
        }
    }

    /** Marks a code as UNUSED again (usedAt cleared). */
    suspend fun markUnused(codeId: String): RecoveryCode? {
        return vault.mutate {
            val code = codeDao.getById(codeId) ?: return@mutate null
            codeDao.markUnused(codeId)
            AuthMappers.toDomain(code.copy(status = "UNUSED", usedAt = null))
        }
    }

    /**
     * Transactionally edits a recovery-code set: [title] + a full replacement
     * [values] list. Codes are matched by whitespace-normalised value so
     * identity (stableId) and USED/UNUSED state are preserved for unchanged
     * codes; removed codes are deleted; new codes get fresh stableId / UNUSED.
     * Duplicate values in the new list are rejected (whole edit rolled back).
     */
    suspend fun editSet(setId: String, title: String, values: List<String>): RecoveryCodeSet {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) throw ValidationException("title is required")

        val normalized = values.map { it.trim() }.filter { it.isNotEmpty() }
        if (normalized.isEmpty()) throw ValidationException("at least one recovery code is required")

        val duplicates = normalized.groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw ValidationException("duplicate recovery code in input")
        }

        return vault.mutate {
            val set = setDao.getById(setId) ?: throw NotFoundException("recovery set not found")
            val existing = codeDao.listBySet(setId)
            val existingByValue = existing.associateBy { it.value.trim() }

            // 1) update set metadata
            setDao.updateTitle(setId, trimmedTitle)

            // 2) diff the code list by value (minimal identity-preserving diff)
            val keptIds = mutableListOf<String>()
            val newValues = mutableListOf<Pair<String, Int>>()
            normalized.forEachIndexed { index, value ->
                val match = existingByValue[value]
                if (match != null) {
                    // Unchanged code: keep its stableId + USED/UNUSED + usedAt,
                    // only refresh the display order.
                    codeDao.updateSortOrder(match.id, index)
                    keptIds += match.id
                } else {
                    newValues += value to index
                }
            }
            // Existing codes that are no longer in the list are deleted (only them).
            existing.filter { it.id !in keptIds }.forEach { codeDao.deleteById(it.id) }

            // 3) insert genuinely new codes with a fresh stableId / UNUSED.
            if (newValues.isNotEmpty()) {
                codeDao.insertAll(
                    newValues.map { (value, index) ->
                        RecoveryCodeEntity(
                            id = UUID.randomUUID().toString(),
                            stableId = UUID.randomUUID().toString(),
                            setId = setId,
                            value = value,
                            status = "UNUSED",
                            usedAt = null,
                            sortOrder = index,
                        )
                    },
                )
            }

            AuthMappers.toDomain(
                set.copy(title = trimmedTitle),
                codeDao.listBySet(setId),
            )
        }
    }

    /**
     * Deletes a recovery-code set (cascade deletes its codes) and returns the
     * full domain value so the caller can restore it on Undo.
     */
    suspend fun deleteSet(setId: String): RecoveryCodeSet? {
        return vault.mutate {
            val set = setDao.getById(setId) ?: return@mutate null
            val domain = AuthMappers.toDomain(set, codeDao.listBySet(setId))
            setDao.deleteById(setId)
            domain
        }
    }

    /**
     * P8 §16–§20 — Moves a Recovery Code Set to a destination Account.
     *
     * The set is an atomic collection: it moves as a whole, preserving the set
     * stableId / title / createdAt and every child code stableId / value /
     * USED-UNUSED / usedAt / sortOrder. Only the parent Account relation
     * changes (P8 §17). Supports cross-provider moves (the destination Account
     * carries its own Provider serviceName).
     *
     * ## Semantics
     *
     * - No title-based dedupe: a destination may already hold a same-titled set;
     *   they simply coexist (title is not identity, P8 §18).
     * - Moving to the current owning Account is a safe no-op (returns unchanged).
     * - If the destination Account already holds a set with the SAME stableId
     *   (theoretically corrupt/impossible), the move is rejected and rolls back
     *   (P8 §18 / §19).
     * - The whole move runs in ONE serialized Room transaction; any failure
     *   rolls back everything, leaving no orphan code (P8 §19).
     */
    suspend fun moveSet(setId: String, destinationAccountId: String): RecoveryCodeSet {
        if (destinationAccountId.isBlank()) throw ValidationException("destination account is required")
        return vault.mutate {
            val set = setDao.getById(setId)
                ?: throw NotFoundException("recovery set not found")
            val destinationAccount = db.authAccountDao().getById(destinationAccountId)
                ?: throw NotFoundException("destination account not found")
            // no-op when already owned by the destination.
            if (set.accountId == destinationAccountId) {
                return@mutate AuthMappers.toDomain(set, codeDao.listBySet(setId))
            }
            // Reject a same-stableId set already present in the destination
            // (schema uniqueness / identity collision) — never silent overwrite.
            val existingInDestination = setDao.listByAccount(destinationAccountId)
                .firstOrNull { it.stableId == set.stableId }
            if (existingInDestination != null) {
                throw ConflictException("a recovery set with this identity already exists in the destination")
            }
            setDao.updateAccountId(set.id, destinationAccount.id)
            AuthMappers.toDomain(set.copy(accountId = destinationAccount.id), codeDao.listBySet(setId))
        }
    }

    /**
     * Restores a previously deleted set (Undo). Preserves the exact set
     * stableId, every child code stableId, values, USED/UNUSED and usedAt —
     * no new logical identity is created. If a set with the same stableId
     * already exists (edge case in the Undo window) the restore is skipped.
     */
    suspend fun restoreSet(set: RecoveryCodeSet) {
        vault.mutate {
            val setEntity = RecoveryCodeSetEntity(
                id = set.id,
                stableId = set.stableId,
                accountId = set.accountId,
                title = set.title,
                createdAt = set.createdAt,
            )
            if (setDao.getById(set.id) == null) {
                setDao.upsert(setEntity)
                if (set.codes.isNotEmpty()) {
                    codeDao.insertAll(
                        set.codes.sortedBy { it.sortOrder }.mapIndexed { index, c ->
                            RecoveryCodeEntity(
                                id = c.id,
                                stableId = c.stableId,
                                setId = c.setId,
                                value = c.value,
                                status = if (c.isUsed) "USED" else "UNUSED",
                                usedAt = c.usedAt,
                                sortOrder = index,
                            )
                        },
                    )
                }
            }
        }
    }
}
