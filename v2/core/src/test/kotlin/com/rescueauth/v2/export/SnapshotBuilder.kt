package com.rescueauth.v2.export

/**
 * Test data builders for the Phase 3A package/merge foundation tests.
 * All builders are deterministic (no randomness, no clocks).
 */
object SnapshotBuilder {

    fun account(
        id: String,
        serviceName: String,
        accountName: String,
        favorite: Boolean = false,
        sortOrder: Long = 0,
        totps: List<VaultTotpCredential> = emptyList(),
        recoverySets: List<VaultRecoveryCodeSet> = emptyList(),
    ) = VaultAccount(
        stableId = id,
        serviceName = serviceName,
        accountName = accountName,
        favorite = favorite,
        sortOrder = sortOrder,
        createdAt = "2024-01-01T00:00:00Z",
        updatedAt = "2024-01-01T00:00:00Z",
        totpCredentials = totps,
        recoveryCodeSets = recoverySets,
    )

    fun totp(
        id: String,
        secret: String = "JBSWY3DPEHPK3PXP",
        algorithm: String = "SHA1",
        digits: Int = 6,
        period: Int = 30,
    ) = VaultTotpCredential(
        stableId = id,
        secretBase32 = secret,
        algorithm = algorithm,
        digits = digits,
        periodSeconds = period,
        createdAt = "2024-01-01T00:00:00Z",
    )

    fun recoverySet(
        id: String,
        title: String = "Recovery codes",
        codes: List<VaultRecoveryCode> = emptyList(),
    ) = VaultRecoveryCodeSet(
        stableId = id,
        title = title,
        createdAt = "2024-01-01T00:00:00Z",
        codes = codes,
    )

    fun recoveryCode(
        id: String,
        value: String,
        status: String = "UNUSED",
        sortOrder: Int = 0,
    ) = VaultRecoveryCode(
        stableId = id,
        value = value,
        status = status,
        usedAt = null,
        sortOrder = sortOrder,
    )
}
