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
        usedAt: String? = null,
        sortOrder: Int = 0,
    ) = VaultRecoveryCode(
        stableId = id,
        value = value,
        status = status,
        usedAt = usedAt,
        sortOrder = sortOrder,
    )

    // ------------------------------------------------------------------
    // Developer Vault builders (five formal types)
    // ------------------------------------------------------------------

    fun signingKey(
        id: String,
        projectName: String = "demo",
        packageName: String = "com.example.demo",
        keystoreFileName: String = "release.jks",
        keystoreBase64: String = DEFAULT_KEYSTORE_BASE64,
        storePassword: String = "store-pass",
        keyAlias: String = "release",
        keyPassword: String = "key-pass",
        title: String = "",
        notes: String? = null,
    ) = VaultAndroidSigningKey(
        stableId = id,
        projectName = projectName,
        packageName = packageName,
        keystoreFileName = keystoreFileName,
        keystoreBase64 = keystoreBase64,
        storePassword = storePassword,
        keyAlias = keyAlias,
        keyPassword = keyPassword,
        title = title,
        notes = notes,
        createdAt = "2024-01-01T00:00:00Z",
        updatedAt = "2024-01-01T00:00:00Z",
    )

    fun apiCredential(
        id: String,
        serviceName: String = "stripe",
        accountName: String = "alice",
        apiKey: String = "sk_test_123",
        apiSecret: String = "secret-abc",
        title: String = "",
        notes: String? = null,
    ) = VaultApiCredential(
        stableId = id,
        serviceName = serviceName,
        accountName = accountName,
        apiKey = apiKey,
        apiSecret = apiSecret,
        title = title,
        notes = notes,
        createdAt = "2024-01-01T00:00:00Z",
        updatedAt = "2024-01-01T00:00:00Z",
    )

    fun sshKey(
        id: String,
        keyName: String = "work",
        publicKey: String = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA...",
        privateKey: String = "-----BEGIN OPENSSH PRIVATE KEY-----\nMIIE...\n-----END OPENSSH PRIVATE KEY-----",
        passphrase: String = "phrase",
        title: String = "",
        notes: String? = null,
    ) = VaultSshKey(
        stableId = id,
        keyName = keyName,
        publicKey = publicKey,
        privateKey = privateKey,
        passphrase = passphrase,
        title = title,
        notes = notes,
        createdAt = "2024-01-01T00:00:00Z",
        updatedAt = "2024-01-01T00:00:00Z",
    )

    fun envVarSet(
        id: String,
        projectName: String = "service-a",
        variables: List<VaultKeyValue> = emptyList(),
        title: String = "",
        notes: String? = null,
    ) = VaultEnvironmentVariableSet(
        stableId = id,
        projectName = projectName,
        variables = variables,
        title = title,
        notes = notes,
        createdAt = "2024-01-01T00:00:00Z",
        updatedAt = "2024-01-01T00:00:00Z",
    )

    fun genericSecret(
        id: String,
        fields: List<VaultKeyValue> = emptyList(),
        title: String = "",
        notes: String? = null,
    ) = VaultGenericSecret(
        stableId = id,
        fields = fields,
        title = title,
        notes = notes,
        createdAt = "2024-01-01T00:00:00Z",
        updatedAt = "2024-01-01T00:00:00Z",
    )

    /** 8 bytes → 12 base64 chars, deterministic, RFC 4648-valid. */
    const val DEFAULT_KEYSTORE_BASE64 = "AAECAwQFBgc="
}
