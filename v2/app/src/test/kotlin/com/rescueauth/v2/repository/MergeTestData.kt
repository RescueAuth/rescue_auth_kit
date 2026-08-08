package com.rescueauth.v2.repository

import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultAccount
import com.rescueauth.v2.export.VaultApiCredential
import com.rescueauth.v2.export.VaultAndroidSigningKey
import com.rescueauth.v2.export.VaultDeveloperEntry
import com.rescueauth.v2.export.VaultEnvironmentVariableSet
import com.rescueauth.v2.export.VaultGenericSecret
import com.rescueauth.v2.export.VaultKeyValue
import com.rescueauth.v2.export.VaultRecoveryCode
import com.rescueauth.v2.export.VaultRecoveryCodeSet
import com.rescueauth.v2.export.VaultSnapshot
import com.rescueauth.v2.export.VaultSshKey
import com.rescueauth.v2.export.VaultTotpCredential

/**
 * Deterministic Phase 3C test builders (mirror of the `:core` SnapshotBuilder,
 * local to the app module so Robolectric repository tests can construct full
 * logical snapshots without pulling test-only helpers from core's test tree).
 */
internal object MergeTestData {

    fun account(
        id: String,
        serviceName: String,
        accountName: String,
        totps: List<VaultTotpCredential> = emptyList(),
        recoverySets: List<VaultRecoveryCodeSet> = emptyList(),
        sortOrder: Long = 0,
    ) = VaultAccount(
        stableId = id,
        serviceName = serviceName,
        accountName = accountName,
        favorite = false,
        notes = null,
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

    fun signingKey(
        id: String,
        projectName: String = "demo",
        packageName: String = "com.example.demo",
        keystoreFileName: String = "release.jks",
        keystoreBase64: String = DEFAULT_KEYSTORE_BASE64,
        storePassword: String = "store-pass",
        keyAlias: String = "release",
        keyPassword: String = "key-pass",
    ) = VaultAndroidSigningKey(
        stableId = id,
        projectName = projectName,
        packageName = packageName,
        keystoreFileName = keystoreFileName,
        keystoreBase64 = keystoreBase64,
        storePassword = storePassword,
        keyAlias = keyAlias,
        keyPassword = keyPassword,
        title = "",
        notes = null,
        createdAt = "2024-01-01T00:00:00Z",
        updatedAt = "2024-01-01T00:00:00Z",
    )

    fun apiCredential(
        id: String,
        serviceName: String = "stripe",
        accountName: String = "alice",
        apiKey: String = "sk_test_123",
        apiSecret: String = "secret-abc",
    ) = VaultApiCredential(
        stableId = id,
        serviceName = serviceName,
        accountName = accountName,
        apiKey = apiKey,
        apiSecret = apiSecret,
        title = "",
        notes = null,
        createdAt = "2024-01-01T00:00:00Z",
        updatedAt = "2024-01-01T00:00:00Z",
    )

    fun sshKey(
        id: String,
        keyName: String = "work",
        publicKey: String = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA...",
        privateKey: String = "-----BEGIN OPENSSH PRIVATE KEY-----\nMIIE...\n-----END OPENSSH PRIVATE KEY-----",
        passphrase: String = "phrase",
    ) = VaultSshKey(
        stableId = id,
        keyName = keyName,
        publicKey = publicKey,
        privateKey = privateKey,
        passphrase = passphrase,
        title = "",
        notes = null,
        createdAt = "2024-01-01T00:00:00Z",
        updatedAt = "2024-01-01T00:00:00Z",
    )

    fun envVarSet(
        id: String,
        projectName: String = "service-a",
        variables: List<VaultKeyValue> = emptyList(),
    ) = VaultEnvironmentVariableSet(
        stableId = id,
        projectName = projectName,
        variables = variables,
        title = "",
        notes = null,
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

    /** All five Developer types as a list (deterministic). */
    fun allFiveDevelopers(): List<VaultDeveloperEntry> = listOf(
        signingKey("dev-signing", keystoreBase64 = "AAECAwQFBgc="),
        apiCredential("dev-api"),
        sshKey("dev-ssh"),
        envVarSet("dev-env", variables = listOf(VaultKeyValue("API_KEY", "x"), VaultKeyValue("URL", "y"))),
        genericSecret("dev-generic", fields = listOf(VaultKeyValue("token", "t-1"))),
    )

    fun fullSnapshot(vararg accounts: VaultAccount, developers: List<VaultDeveloperEntry> = emptyList()) =
        VaultSnapshot(
            accounts = accounts.toList(),
            developerEntries = developers,
            scope = SnapshotScope.FULL_VAULT,
        )

    const val DEFAULT_KEYSTORE_BASE64 = "AAECAwQFBgc="
}
