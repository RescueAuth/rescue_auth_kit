package com.rescueauth.v2.repository

import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.session.SessionManager

/**
 * Production wiring: `Session unlocked → active DB → repository → ViewModel`.
 *
 * [MainActivity] registers the active [SessionManager] here. When the session
 * is UNLOCKED ([SessionManager.databaseOrNull] returns the open encrypted DB)
 * the caller can obtain the shared [AuthenticatorRepository]; when locked or
 * never unlocked the repository is `null` and the UI stays in a locked/empty
 * state. A single repository instance is cached per DB handle so all writes
 * funnel through one [VaultRepository] mutex (serialization guarantee).
 *
 * This is deliberately the *only* global access point for the production
 * Authenticator data path — it keeps `SessionManager` / `SecureSessionStateMachine`
 * / Phase 2 security semantics untouched.
 */
object VaultAccess {

    @Volatile
    var sessionManager: SessionManager? = null

    @Volatile
    private var cachedDb: RescueAuthDatabase? = null

    @Volatile
    private var cachedAuthRepository: AuthenticatorRepository? = null

    /** Drops cached state (called on app teardown). */
    fun clear() {
        cachedDb = null
        cachedAuthRepository = null
        sessionManager = null
    }

    /** @return the production Authenticator repository, or null while locked. */
    fun authenticatorRepository(): AuthenticatorRepository? {
        val sm = sessionManager ?: return null
        val db = sm.databaseOrNull() ?: return null
        val cached = cachedAuthRepository
        if (cached != null && cachedDb === db) return cached
        val repo = AuthenticatorRepository(VaultRepository(db, sm.sessionState), db, sm.sessionState)
        cachedDb = db
        cachedAuthRepository = repo
        return repo
    }
}
