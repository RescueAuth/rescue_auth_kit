package com.rescueauth.v2.domain

/**
 * Domain model of a TOTP credential (Phase 4 P1).
 *
 * Sits between Room and the ViewModel so UI components never receive a Room
 * entity and never need to know about the database. The Base32 secret is a
 * first-class domain field but is **never** exposed through the UI model — it
 * only flows into the TOTP core for code generation.
 */
data class TotpCredential(
    val id: String,
    val stableId: String,
    val accountId: String,
    val secretBase32: String,
    val algorithm: String,
    val digits: Int,
    val periodSeconds: Int,
    val createdAt: String,
)

/** An account in the Authenticator hierarchy (Provider → Account → Credential). */
data class AuthAccount(
    val id: String,
    val stableId: String,
    val serviceName: String,
    val accountName: String,
    val sortOrder: Long,
    val createdAt: String,
    val updatedAt: String,
    val favorite: Boolean = false,
    val notes: String? = null,
)

/**
 * A recovery-code set (Phase 4 P3).
 *
 * Belongs to exactly one [AuthAccount] (Provider → Account → Recovery Code
 * Set → Recovery Code[]). The set carries the stable logical identity that
 * survives export/import (Phase 3A); codes preserve their own stableId and
 * used/unused state across edits. The plaintext code values are secret-like
 * data that must never be logged or indexed.
 */
data class RecoveryCodeSet(
    val id: String,
    val stableId: String,
    val accountId: String,
    val title: String,
    val createdAt: String,
    val codes: List<RecoveryCode> = emptyList(),
) {
    val usedCount: Int get() = codes.count { it.isUsed }
    val totalCount: Int get() = codes.size
    val remainingCount: Int get() = totalCount - usedCount
}

/** A single recovery code inside a [RecoveryCodeSet]. */
data class RecoveryCode(
    val id: String,
    val stableId: String,
    val setId: String,
    val value: String,
    val isUsed: Boolean,
    val usedAt: String? = null,
    val sortOrder: Int = 0,
)
