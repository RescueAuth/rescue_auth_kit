package com.rescueauth.v2.repository

import com.rescueauth.v2.database.AuthAccountDao
import com.rescueauth.v2.database.AuthAccountEntity
import com.rescueauth.v2.database.ImportRecordDao
import com.rescueauth.v2.database.ImportRecordEntity
import com.rescueauth.v2.database.RecoveryCodeDao
import com.rescueauth.v2.database.RecoveryCodeEntity
import com.rescueauth.v2.database.RecoveryCodeSetDao
import com.rescueauth.v2.database.RecoveryCodeSetEntity
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.database.TotpCredentialDao
import com.rescueauth.v2.database.TotpCredentialEntity
import com.rescueauth.v2.legacy.LegacyImportBundle
import com.rescueauth.v2.legacy.LegacyToV2Mapper
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
    class InvalidImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val mutex = Mutex()

    private val accountDao: AuthAccountDao get() = db.authAccountDao()
    private val totpDao: TotpCredentialDao get() = db.totpCredentialDao()
    private val recoverySetDao: RecoveryCodeSetDao get() = db.recoveryCodeSetDao()
    private val recoveryCodeDao: RecoveryCodeDao get() = db.recoveryCodeDao()
    private val importDao: ImportRecordDao get() = db.importRecordDao()

    // ------------------------------------------------------------------
    // Reads (no lock required — DB handle is closed on lock so calls fail)
    // ------------------------------------------------------------------

    fun observeAccounts() = accountDao.observeAll()
    fun observeTotp(accountId: String) = totpDao.observeByAccount(accountId)
    fun observeRecoverySets(accountId: String) = recoverySetDao.observeByAccount(accountId)

    // ------------------------------------------------------------------
    // Mutations (serialized)
    // ------------------------------------------------------------------

    /**
     * Imports a validated [LegacyImportBundle] in a single transaction.
     *
     * Phase 3 reset: there is NO PRE_IMPORT backup checkpoint anymore — the
     * automatic-backup snapshot model was removed (manual Export Package only).
     * The import itself is still transactional: any failure rolls back the
     * whole import. (A future Phase 3C step funnels the logical records
     * through the Merge Planner; legacy import currently maps directly to
     * Room rows exactly as Phase 2 established.)
     */
    suspend fun importLegacy(bundle: LegacyImportBundle): ImportSummary {
        checkUnlocked()
        return mutex.withLock {
            db.withTransaction {
                val result = LegacyToV2Mapper.map(bundle)
                if (result.accounts.isEmpty() && result.report.notImported.isEmpty()) {
                    throw InvalidImportException("Nothing to import")
                }

                val now = java.time.Instant.now().toString()
                val accounts = result.accounts.map { a ->
                    AuthAccountEntity(
                        id = a.id,
                        stableId = a.id,
                        serviceName = a.serviceName,
                        accountName = a.accountName,
                        favorite = a.favorite,
                        notes = a.notes,
                        sortOrder = a.sortOrder,
                        createdAt = a.createdAt,
                        updatedAt = a.updatedAt,
                        legacySourceId = a.legacySourceId,
                    )
                }
                accountDao.insertAll(accounts)

                val totps = mutableListOf<TotpCredentialEntity>()
                val sets = mutableListOf<RecoveryCodeSetEntity>()
                val codes = mutableListOf<RecoveryCodeEntity>()
                for (a in result.accounts) {
                    for (t in a.totpCredentials) {
                        totps += TotpCredentialEntity(
                            id = t.id,
                            stableId = t.id,
                            accountId = a.id,
                            secretBase32 = t.secretBase32,
                            algorithm = t.algorithm,
                            digits = t.digits,
                            periodSeconds = t.periodSeconds,
                            createdAt = a.createdAt,
                            legacySourceId = t.legacySourceId,
                        )
                    }
                    for (s in a.recoveryCodeSets) {
                        sets += RecoveryCodeSetEntity(
                            id = s.id,
                            stableId = s.id,
                            accountId = a.id,
                            title = s.title,
                            createdAt = a.createdAt,
                            legacySourceId = s.legacySourceId,
                        )
                        codes += s.codes.map { c ->
                            RecoveryCodeEntity(
                                id = c.id,
                                stableId = c.id,
                                setId = s.id,
                                value = c.value,
                                status = c.status,
                                usedAt = c.usedAt,
                                sortOrder = c.sortOrder,
                            )
                        }
                    }
                }
                totpDao.insertAll(totps)
                recoverySetDao.insertAll(sets)
                recoveryCodeDao.insertAll(codes)

                val fingerprint = bundle.schemaVersion.toString() + ":" + result.accounts.size
                importDao.insert(
                    ImportRecordEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        stableId = java.util.UUID.randomUUID().toString(),
                        sourceType = "LEGACY_RAKVAULT",
                        sourceFingerprint = fingerprint,
                        importedAt = now,
                        itemCount = result.accounts.size,
                        warningCount = result.report.notImported.size + bundle.developerCount,
                    )
                )

                ImportSummary(
                    importedAccounts = result.accounts.size,
                    notImported = result.report.notImported.map { it.sourceId },
                    warningCount = result.report.notImported.size + bundle.developerCount,
                )
            }
        }
    }

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

    /** Toggles an account's favorite flag. */
    suspend fun setFavorite(accountId: String, favorite: Boolean) {
        checkUnlocked()
        mutex.withLock {
            db.withTransaction {
                accountDao.setFavorite(accountId, favorite, java.time.Instant.now().toString())
            }
        }
    }

    suspend fun deleteAccount(accountId: String) {
        checkUnlocked()
        mutex.withLock {
            db.withTransaction {
                accountDao.deleteById(accountId)
            }
        }
    }

    private fun checkUnlocked() {
        if (!session.isUnlocked()) throw SessionLockedException()
    }
}

/** Result of a legacy import. */
data class ImportSummary(
    val importedAccounts: Int,
    val notImported: List<String>,
    val warningCount: Int,
)
