package com.rescueauth.v2.repository

import com.rescueauth.v2.BuildConfig
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.exportimport.ExportImportService
import com.rescueauth.v2.session.SessionManager

/**
 * Production wiring: `Session unlocked → active DB → repository → ViewModel`.
 *
 * [MainActivity] registers the active [SessionManager] here. When the session
 * is UNLOCKED ([SessionManager.databaseOrNull] returns the open encrypted DB)
 * the caller can obtain the shared repositories; when locked or never unlocked
 * they are `null` and the UI stays in a locked/empty state.
 *
 * A **single** [VaultRepository] instance is cached per DB handle so all
 * writes (Authenticator CRUD, import/merge apply, legacy import) funnel
 * through one mutex (serialization guarantee, ADR-0003 §3). The
 * [AuthenticatorRepository] and [ExportImportService] are built on top of that
 * shared vault repository, so the Export/Import use-cases and the daily-use
 * CRUD can never interleave a write with a transactional merge.
 *
 * This is deliberately the *only* global access point for the production data
 * path — it keeps `SessionManager` / `SecureSessionStateMachine` / Phase 2
 * security semantics untouched.
 */
object VaultAccess {

    @Volatile
    var sessionManager: SessionManager? = null

    @Volatile
    private var cachedDb: RescueAuthDatabase? = null

    @Volatile
    private var cachedVaultRepository: VaultRepository? = null

    @Volatile
    private var cachedAuthRepository: AuthenticatorRepository? = null

    @Volatile
    private var cachedRecoveryRepository: RecoveryCodeRepository? = null

    @Volatile
    private var cachedExportImportService: ExportImportService? = null

    /** Drops cached state (called on app teardown). */
    fun clear() {
        cachedDb = null
        cachedVaultRepository = null
        cachedAuthRepository = null
        cachedRecoveryRepository = null
        cachedExportImportService = null
        sessionManager = null
    }

    /**
     * @return the shared production [VaultRepository] for the current open DB,
     *   or null while locked / never unlocked.
     */
    fun vaultRepository(): VaultRepository? {
        val sm = sessionManager ?: return null
        val db = sm.databaseOrNull() ?: return null
        val cached = cachedVaultRepository
        if (cached != null && cachedDb === db) return cached
        val repo = VaultRepository(db, sm.sessionState)
        cachedDb = db
        cachedVaultRepository = repo
        return repo
    }

    /** @return the production Authenticator repository, or null while locked. */
    fun authenticatorRepository(): AuthenticatorRepository? {
        val vault = vaultRepository() ?: return null
        val sm = sessionManager ?: return null
        val db = sm.databaseOrNull() ?: return null
        val cached = cachedAuthRepository
        if (cached != null && cachedDb === db) return cached
        val repo = AuthenticatorRepository(vault, db, sm.sessionState)
        cachedDb = db
        cachedAuthRepository = repo
        return repo
    }

    /**
     * @return the production Recovery Codes repository for the current open DB,
     *   or null while locked. Shares the single [VaultRepository] mutex so all
     *   writes (Recovery CRUD, Authenticator CRUD, export/import) stay
     *   serialized.
     */
    fun recoveryRepository(): RecoveryCodeRepository? {
        val vault = vaultRepository() ?: return null
        val sm = sessionManager ?: return null
        val db = sm.databaseOrNull() ?: return null
        val cached = cachedRecoveryRepository
        if (cached != null && cachedDb === db) return cached
        val repo = RecoveryCodeRepository(vault, db, sm.sessionState)
        cachedDb = db
        cachedRecoveryRepository = repo
        return repo
    }

    /**
     * @return the Phase 3D Export/Import use-case service for the current open
     *   DB, or null while locked.
     */
    fun exportImportService(): ExportImportService? {
        val vault = vaultRepository() ?: return null
        val sm = sessionManager ?: return null
        val db = sm.databaseOrNull() ?: return null
        val cached = cachedExportImportService
        if (cached != null && cachedDb === db) return cached
        val svc = ExportImportService(vault, BuildConfig.VERSION_NAME)
        cachedDb = db
        cachedExportImportService = svc
        return svc
    }
}
