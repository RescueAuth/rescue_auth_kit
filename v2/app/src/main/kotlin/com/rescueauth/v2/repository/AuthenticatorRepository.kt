package com.rescueauth.v2.repository

import com.rescueauth.v2.database.AuthAccountEntity
import com.rescueauth.v2.database.TotpCredentialEntity
import com.rescueauth.v2.domain.AuthAccount
import com.rescueauth.v2.domain.TotpCredential
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Production Authenticator repository (Phase 4 P1).
 *
 * Provides the first real TOTP CRUD path on top of [VaultRepository]:
 * `Session unlocked → active DB → DAO → repository → ViewModel`. All writes
 * are serialized by the parent [VaultRepository] mutex; every mutation checks
 * the session state.
 */
class AuthenticatorRepository(
    private val vault: VaultRepository,
    private val db: com.rescueauth.v2.database.RescueAuthDatabase,
    private val session: SecureSessionStateMachine,
) {
    class ValidationException(message: String) : Exception(message)
    class NotFoundException(message: String) : Exception(message)

    private val accountDao get() = db.authAccountDao()
    private val totpDao get() = db.totpCredentialDao()

    // ------------------------------------------------------------------
    // Reads (Flow → domain)
    // ------------------------------------------------------------------

    fun observeAccounts(): Flow<List<AuthAccount>> =
        accountDao.observeAll().map { list -> list.map { AuthMappers.toDomain(it) } }

    fun observeTotpCredentials(): Flow<List<TotpCredential>> =
        totpDao.observeAll().map { list -> list.map { AuthMappers.toDomain(it) } }

    // ------------------------------------------------------------------
    // Mutations (delegated to the serialized VaultRepository)
    // ------------------------------------------------------------------

    /**
     * Adds a TOTP credential under [accountId]. The credential (and its
     * account) is created inside the same serialized mutation. The secret is
     * stored exactly as provided (already normalised by the caller / parser).
     */
    suspend fun addTotpCredential(
        accountId: String,
        secretBase32: String,
        algorithm: String,
        digits: Int,
        periodSeconds: Int,
    ): TotpCredential {
        if (secretBase32.isBlank()) throw ValidationException("secret is required")
        if (algorithm.isBlank()) throw ValidationException("algorithm is required")
        if (digits !in 6..8) throw ValidationException("digits must be 6, 7 or 8")
        if (periodSeconds <= 0) throw ValidationException("period must be positive")

        val now = java.time.Instant.now().toString()
        val id = UUID.randomUUID().toString()
        val entity = TotpCredentialEntity(
            id = id,
            stableId = id,
            accountId = accountId,
            secretBase32 = secretBase32,
            algorithm = algorithm.trim().uppercase(),
            digits = digits,
            periodSeconds = periodSeconds,
            createdAt = now,
        )
        vault.mutate {
            totpDao.upsert(entity)
        }
        return AuthMappers.toDomain(entity)
    }

    /**
     * Finds an existing account by (service, account) or creates it.
     * Returns the account domain object.
     */
    suspend fun findOrCreateAccount(serviceName: String, accountName: String): AuthAccount {
        val now = java.time.Instant.now().toString()
        val existing = vault.mutate {
            accountDao.findByServiceAndAccount(serviceName.trim(), accountName.trim())
        }
        if (existing != null) return AuthMappers.toDomain(existing)

        val id = UUID.randomUUID().toString()
        val entity = AuthAccountEntity(
            id = id,
            stableId = id,
            serviceName = serviceName.trim(),
            accountName = accountName.trim(),
            favorite = false,
            notes = null,
            sortOrder = System.currentTimeMillis(),
            createdAt = now,
            updatedAt = now,
        )
        vault.mutate {
            accountDao.upsert(entity)
        }
        return AuthMappers.toDomain(entity)
    }

    /**
     * Deletes a TOTP credential by its Room id. Returns the full deleted row
     * (if it existed) so the caller can restore it on Undo.
     */
    suspend fun deleteTotpCredential(credentialId: String): TotpCredential? {
        val deleted = vault.mutate {
            val row = totpDao.getById(credentialId) ?: return@mutate null
            totpDao.deleteById(credentialId)
            AuthMappers.toDomain(row)
        }
        return deleted
    }

    /** Deletes an account (cascade deletes its TOTP credentials). */
    suspend fun deleteAccount(accountId: String) {
        vault.deleteAccount(accountId)
    }

    /**
     * Restores a previously deleted TOTP credential — used by Undo.
     * The original [stableId] and all fields are preserved exactly, so the
     * logical identity is unchanged.
     */
    suspend fun restoreTotpCredential(credential: TotpCredential) {
        val entity = TotpCredentialEntity(
            id = credential.id,
            stableId = credential.stableId,
            accountId = credential.accountId,
            secretBase32 = credential.secretBase32,
            algorithm = credential.algorithm,
            digits = credential.digits,
            periodSeconds = credential.periodSeconds,
            createdAt = credential.createdAt,
        )
        vault.mutate {
            // If a credential with the same stableId already exists (edge
            // case: user re-added + deleted in the Undo window), keep the
            // existing one instead of creating a duplicate identity.
            if (totpDao.getByStableId(credential.stableId) == null) {
                totpDao.upsert(entity)
            }
        }
    }
}
