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
