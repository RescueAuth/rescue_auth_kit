package com.rescueauth.v2.legacy

import com.rescueauth.v2.export.PackageValidator
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultAndroidSigningKey
import com.rescueauth.v2.export.VaultApiCredential
import com.rescueauth.v2.export.VaultEnvironmentVariableSet
import com.rescueauth.v2.export.VaultGenericSecret
import com.rescueauth.v2.export.VaultSshKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5A — Legacy v1 → shared VaultSnapshot mapping tests.
 *
 * Fixtures under `legacy-fixtures/phase5a/` were generated INDEPENDENTLY of
 * the Dart tooling (Python + argon2-cffi + PyNaCl, same frozen v1.2.0 wire
 * protocol) — provenance is recorded in docs/PHASE5A_REPORT.md.
 */
class LegacyVaultSnapshotMapperTest {

    private fun loadFixture(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("legacy-fixtures/phase5a/$name.rakvault")
            ?.use { it.readBytes() }
            ?: error("fixture not found: $name")

    private fun importAndMap(
        name: String,
        password: String,
    ): Pair<ByteArray, com.rescueauth.v2.export.VaultSnapshot> {
        val bytes = loadFixture(name)
        val bundle = LegacyRakVaultImporter().import(bytes, password)
        val fingerprint = LegacyVaultSnapshotMapper.fingerprintOfEncryptedBytes(bytes)
        return bytes to LegacyVaultSnapshotMapper.map(bundle, fingerprint)
    }

    // ------------------------------------------------------------------
    // Schema 1 — entry-centric, one account per TOTP / recovery set
    // ------------------------------------------------------------------

    @Test
    fun `schema1 maps one account per totp and recovery set`() {
        val (_, snapshot) = importAndMap("phase5a_schema1_basic", "test-password-1")

        assertEquals(SnapshotScope.FULL_VAULT, snapshot.scope)
        assertEquals(2, snapshot.accounts.size) // 1 TOTP + 1 recovery set
        assertEquals(0, snapshot.developerEntries.size)

        val totpAccount = snapshot.accounts.first { it.totpCredentials.isNotEmpty() }
        assertEquals("GitHub", totpAccount.serviceName)
        assertEquals("alice@example.com", totpAccount.accountName)
        assertEquals("JBSWY3DPEHPK3PXP", totpAccount.totpCredentials.single().secretBase32)
        assertEquals("SHA1", totpAccount.totpCredentials.single().algorithm)
        assertEquals(6, totpAccount.totpCredentials.single().digits)
        assertEquals(30, totpAccount.totpCredentials.single().periodSeconds)

        val recoveryAccount = snapshot.accounts.first { it.recoveryCodeSets.isNotEmpty() }
        assertEquals("GitHub backup", recoveryAccount.serviceName)
        assertEquals("AAAA-BBBB-CCCC", recoveryAccount.recoveryCodeSets.single().codes[0].value)
        assertEquals("UNUSED", recoveryAccount.recoveryCodeSets.single().codes[0].status)
        assertEquals(null, recoveryAccount.recoveryCodeSets.single().codes[0].usedAt)

        // Shared validation must accept the mapped snapshot.
        PackageValidator.validate(snapshot)
    }

    // ------------------------------------------------------------------
    // Schema 2 — developer entries must be fully covered
    // ------------------------------------------------------------------

    @Test
    fun `schema2 maps all five developer types`() {
        val (_, snapshot) = importAndMap("phase5a_schema2_dev", "test-password-2")

        assertEquals(3, snapshot.accounts.size) // 2 TOTP + 1 recovery set
        assertEquals(5, snapshot.developerEntries.size)
        PackageValidator.validate(snapshot)

        val byKind = snapshot.developerEntries.groupBy { it::class.simpleName }

        val signing = byKind.getValue("VaultAndroidSigningKey").single() as VaultAndroidSigningKey
        assertEquals("rescueauth", signing.projectName)
        assertEquals("com.rescueauth.app", signing.packageName)
        assertEquals("release.jks", signing.keystoreFileName)
        assertEquals("AAECAwQFBgc=", signing.keystoreBase64) // exact byte round-trip
        assertEquals("store-pw", signing.storePassword)
        assertEquals("release", signing.keyAlias)
        assertEquals("key-pw", signing.keyPassword)

        val api = byKind.getValue("VaultApiCredential").single() as VaultApiCredential
        assertEquals("stripe", api.serviceName)
        assertEquals("alice", api.accountName)
        assertEquals("sk-test-123", api.apiKey)
        assertEquals("secret-abc", api.apiSecret)

        val ssh = byKind.getValue("VaultSshKey").single() as VaultSshKey
        assertEquals("work", ssh.keyName)
        assertEquals("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA...", ssh.publicKey)
        assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----\nMIIE...\n-----END OPENSSH PRIVATE KEY-----", ssh.privateKey)
        assertEquals("phrase", ssh.passphrase)

        val env = byKind.getValue("VaultEnvironmentVariableSet").single() as VaultEnvironmentVariableSet
        assertEquals("service-a", env.projectName)
        assertEquals(listOf("API_KEY", "URL"), env.variables.map { it.key })
        assertEquals(listOf("x", "y"), env.variables.map { it.value })

        val generic = byKind.getValue("VaultGenericSecret").single() as VaultGenericSecret
        assertEquals(listOf("password"), generic.fields.map { it.key })
        assertEquals(listOf("wifi-1"), generic.fields.map { it.value })
    }

    // ------------------------------------------------------------------
    // Schema 3 — provider/account hierarchy preserved
    // ------------------------------------------------------------------

    @Test
    fun `schema3 keeps provider account hierarchy and recovery mapping`() {
        val (_, snapshot) = importAndMap("phase5a_schema3_full", "test-password-3")

        assertEquals(2, snapshot.accounts.size)
        assertEquals(5, snapshot.developerEntries.size)
        PackageValidator.validate(snapshot)

        val alice = snapshot.accounts.first { it.accountName == "alice@example.com" }
        assertEquals("GitHub", alice.serviceName)
        assertEquals(1, alice.totpCredentials.size)
        assertEquals(1, alice.recoveryCodeSets.size)
        assertEquals("JBSWY3DPEHPK3PXP", alice.totpCredentials.single().secretBase32)

        val bob = snapshot.accounts.first { it.accountName == "bob@example.com" }
        assertEquals("Google", bob.serviceName)
        assertEquals(2, bob.totpCredentials.size)
        val algorithms = bob.totpCredentials.map { it.algorithm }.toSet()
        assertEquals(setOf("SHA256", "SHA512"), algorithms)
        assertEquals(8, bob.totpCredentials.first { it.algorithm == "SHA512" }.digits)
        assertEquals(60, bob.totpCredentials.first { it.algorithm == "SHA512" }.periodSeconds)
    }

    // ------------------------------------------------------------------
    // Deterministic stableId / idempotence
    // ------------------------------------------------------------------

    @Test
    fun `same fixture mapped twice produces identical stableIds`() {
        val (_, s1) = importAndMap("phase5a_schema3_full", "test-password-3")
        val (_, s2) = importAndMap("phase5a_schema3_full", "test-password-3")

        assertEquals(
            allStableIds(s1),
            allStableIds(s2),
        )
    }

    @Test
    fun `different source files never collide stableIds`() {
        val (b1, s1) = importAndMap("phase5a_schema3_full", "test-password-3")
        val (b2, s2) = importAndMap("phase5a_unicode", "test-password-1")

        assertNotEquals(
            LegacyVaultSnapshotMapper.fingerprintOfEncryptedBytes(b1),
            LegacyVaultSnapshotMapper.fingerprintOfEncryptedBytes(b2),
        )
        // The unicode fixture is a *different* vault; its stableIds must not
        // equal those of the full schema-3 fixture even for the same kind.
        assertTrue(
            allStableIds(s1).none { it in allStableIds(s2) },
        )
    }

    @Test
    fun `stableId never contains plaintext secret`() {
        val (bytes, snapshot) = importAndMap("phase5a_schema3_full", "test-password-3")
        val fingerprint = LegacyVaultSnapshotMapper.fingerprintOfEncryptedBytes(bytes)

        val stableIds = allStableIds(snapshot)
        assertTrue(stableIds.all { it.startsWith("legacy:") })

        // No raw secret / password / developer payload substring may appear.
        val secrets = listOf(
            "test-password-3",
            "JBSWY3DPEHPK3PXP",      // TOTP secret
            "sk-test-123",            // API key
            "secret-abc",             // API secret
            "AAECAwQFBgc=",          // keystore bytes base64
            "-----BEGIN OPENSSH",     // SSH private key
        )
        for (s in stableIds) {
            for (secret in secrets) {
                assertTrue(
                    "stableId must not contain plaintext secret: $s",
                    !s.contains(secret),
                )
            }
        }
        assertTrue(fingerprint != null && !fingerprint.contains("test-password-3"))
        assertTrue(fingerprint.length >= 20)
    }

    @Test
    fun `unicode metadata is preserved`() {
        val (_, snapshot) = importAndMap("phase5a_unicode", "test-password-1")
        PackageValidator.validate(snapshot)

        val unicodeAccount = snapshot.accounts.first { it.serviceName.contains("中文") }
        assertEquals("雪域@example.com", unicodeAccount.accountName)
        assertTrue(unicodeAccount.notes == null)
    }

    @Test
    fun `fingerprint is stable for identical bytes and differs for different bytes`() {
        val bytes = loadFixture("phase5a_schema1_basic")
        val f1 = LegacyVaultSnapshotMapper.fingerprintOfEncryptedBytes(bytes)
        val f2 = LegacyVaultSnapshotMapper.fingerprintOfEncryptedBytes(bytes)
        assertEquals(f1, f2)

        val other = loadFixture("phase5a_schema2_dev")
        assertNotEquals(f1, LegacyVaultSnapshotMapper.fingerprintOfEncryptedBytes(other))
    }

    private fun allStableIds(s: com.rescueauth.v2.export.VaultSnapshot): List<String> {
        val out = mutableListOf<String>()
        for (a in s.accounts) {
            out += a.stableId
            out += a.totpCredentials.map { it.stableId }
            out += a.recoveryCodeSets.flatMap { set ->
                listOf(set.stableId) + set.codes.map { it.stableId }
            }
        }
        out += s.developerEntries.map { it.stableId }
        return out
    }
}
