package com.rescueauth.v2.legacy

import com.rescueauth.v2.export.PackageValidator
import com.rescueauth.v2.export.VaultAndroidSigningKey
import com.rescueauth.v2.export.VaultApiCredential
import com.rescueauth.v2.export.VaultEnvironmentVariableSet
import com.rescueauth.v2.export.VaultGenericSecret
import com.rescueauth.v2.export.VaultSnapshot
import com.rescueauth.v2.export.VaultSshKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5A (CR fix) — real-producer interoperability for the frozen v1
 * implementation (Blocker 3).
 *
 * `frozen_v1_producer_schema3.rakvault` (and its alt-backup twin) were
 * produced by the **actual frozen v1.2.0 implementation** — `vault_crypto.dart`
 * + `vault_models.dart` copied VERBATIM from tag `v1.2.0` (tool:
 * `tools/legacy_fixtures_frozen/`, since removed with the legacy Flutter code),
 * NOT by Python, NOT by the Kotlin
 * test-encoder, NOT by the standalone Dart mirror tool. Provenance is
 * recorded in docs/PHASE5A_REPORT.md §15.
 *
 * This test locks the full pipeline on that real-producer fixture:
 *   frozen-v1 .rakvault → LegacyRakVaultImporter → LegacyVaultSnapshotMapper
 *   → expected VaultSnapshot
 *
 * Covering: TOTP, Recovery, and all five Developer entry types.
 */
class LegacyFrozenV1InteropTest {

    private fun loadFixture(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("legacy-fixtures/phase5a/$name.rakvault")
            ?.use { it.readBytes() }
            ?: error("fixture not found: $name")

    private fun importAndMap(name: String): Pair<ByteArray, VaultSnapshot> {
        val bytes = loadFixture(name)
        val bundle = LegacyRakVaultImporter().import(bytes, "test-password-frozen")
        val fingerprint = LegacyVaultSnapshotMapper.fingerprintOfEncryptedBytes(bytes)
        val snapshot = LegacyVaultSnapshotMapper.map(bundle, fingerprint)
        PackageValidator.validate(snapshot)
        return bytes to snapshot
    }

    @Test
    fun `frozen v1 produced vault imports and maps to expected snapshot`() {
        val (bytes, snapshot) = importAndMap("frozen_v1_producer_schema3")

        assertTrue(bytes.isNotEmpty())
        assertEquals(1, snapshot.accounts.size)
        assertEquals(5, snapshot.developerEntries.size)

        // --- TOTP credential ---
        val account = snapshot.accounts.single()
        assertEquals("GitHub", account.serviceName)
        assertEquals("alice@example.com", account.accountName)
        val totp = account.totpCredentials.single()
        assertEquals("JBSWY3DPEHPK3PXP", totp.secretBase32)
        assertEquals("SHA1", totp.algorithm)
        assertEquals(6, totp.digits)
        assertEquals(30, totp.periodSeconds)

        // --- Recovery codes ---
        val set = account.recoveryCodeSets.single()
        assertEquals("GitHub", set.title)
        assertEquals(listOf("AAAA-BBBB-CCCC", "DDDD-EEEE-FFFF", "1111-2222-3333"),
            set.codes.map { it.value })
        assertTrue(set.codes.all { it.status == "UNUSED" && it.usedAt == null })

        // --- Developer: all five types, field-by-field ---
        val byKind = snapshot.developerEntries.groupBy { it::class.simpleName }

        val signing = byKind.getValue("VaultAndroidSigningKey").single() as VaultAndroidSigningKey
        assertEquals("rescueauth", signing.projectName)
        assertEquals("com.rescueauth.app", signing.packageName)
        assertEquals("release.jks", signing.keystoreFileName)
        assertEquals("AAECAwQFBgc=", signing.keystoreBase64)
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

    @Test
    fun `frozen v1 producer stableIds are stable and namespaced`() {
        val (_, snapshot) = importAndMap("frozen_v1_producer_schema3")

        val all = mutableListOf<String>()
        snapshot.accounts.forEach { a ->
            all += a.stableId
            all += a.totpCredentials.map { it.stableId }
            all += a.recoveryCodeSets.flatMap { s -> listOf(s.stableId) + s.codes.map { it.stableId } }
        }
        all += snapshot.developerEntries.map { it.stableId }

        assertTrue(all.all { it.startsWith("legacy:") })
        assertEquals(all.size, all.toSet().size) // no duplicate stableIds
    }
}
