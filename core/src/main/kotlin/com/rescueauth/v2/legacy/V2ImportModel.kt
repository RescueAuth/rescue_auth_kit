package com.rescueauth.v2.legacy

/**
 * Pure v2 import model produced by [LegacyToV2Mapper].
 *
 * These are plain, database-agnostic value objects — phase 2 maps them onto
 * the Room entities inside a single transaction. Keeping them in the pure JVM
 * `core` module lets the mapper (and its correctness tests) run without an
 * Android device.
 */
data class V2AuthAccount(
    val id: String,
    val serviceName: String,
    val accountName: String,
    val favorite: Boolean = false,
    val notes: String? = null,
    val sortOrder: Long,
    val createdAt: String,
    val updatedAt: String,
    val legacySourceId: String?,
    val totpCredentials: MutableList<V2TotpCredential> = mutableListOf(),
    val recoveryCodeSets: MutableList<V2RecoveryCodeSet> = mutableListOf(),
)

data class V2TotpCredential(
    val id: String,
    val accountId: String,
    val secretBase32: String,
    val algorithm: String,
    val digits: Int,
    val periodSeconds: Int,
    val createdAt: String,
    val legacySourceId: String?,
)

data class V2RecoveryCodeSet(
    val id: String,
    val accountId: String,
    val title: String,
    val createdAt: String,
    val legacySourceId: String?,
    val codes: List<V2RecoveryCode> = emptyList(),
)

data class V2RecoveryCode(
    val id: String,
    val setId: String,
    val value: String,
    val status: String = "UNUSED", // UNUSED | USED
    val usedAt: String? = null,
    val sortOrder: Int,
)
