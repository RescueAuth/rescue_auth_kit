package com.rescueauth.v2.exportimport

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.export.VaultApiCredential
import com.rescueauth.v2.export.VaultGenericSecret
import com.rescueauth.v2.export.VaultSshKey
import com.rescueauth.v2.export.codec.PortablePackageCodec
import com.rescueauth.v2.repository.DeveloperRepository
import com.rescueauth.v2.repository.ImportOutcome
import com.rescueauth.v2.repository.VaultRepository
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 4 P4 Developer Vault package integration tests (Issue #20 §22, §29).
 *
 * Proves that the P4 first-batch CRUD (API Credential / SSH Key / Generic
 * Secret) written through the real [DeveloperRepository] naturally enters:
 *
 * - the Full Vault logical snapshot,
 * - an encoded portable package (.rakpkg),
 * - decode → import into an EMPTY vault with exact logical data preserved,
 * - a second import is idempotent,
 * - the same stableId with a changed logical payload remains a CONFLICT
 *   (unchanged merge semantics).
 *
 * The SAF system picker is NOT exercised here (Issue #20 §22).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeveloperPackageIntegrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine
    private lateinit var vault: VaultRepository
    private lateinit var devRepo: DeveloperRepository
    private lateinit var service: ExportImportService

    private val pin: CharArray = "123456".toCharArray()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = SecureSessionStateMachine()
        session.beginAuthentication()
        session.onAuthenticationSuccess()
        vault = VaultRepository(db, session)
        devRepo = DeveloperRepository(vault, db, session)
        service = ExportImportService(vault, "test-1.0.0")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedThreeTypes() {
        devRepo.createApiCredential(
            title = "Stripe API", notes = "prod",
            serviceName = "stripe", accountName = "alice@example.com",
            apiKey = "sk_live_111", apiSecret = "api-secret-111",
        )
        devRepo.createSshKey(
            title = "deploy key", notes = null,
            keyName = "deploy-2026",
            publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIEXAMPLE",
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nMIIEexample\n-----END OPENSSH PRIVATE KEY-----",
            passphrase = "phrase-1",
        )
        devRepo.createGenericSecret(
            title = "Wi-Fi", notes = "guest",
            fields = listOf(
                com.rescueauth.v2.export.VaultKeyValue("network", "guest-net"),
                com.rescueauth.v2.export.VaultKeyValue("password", "wifi-pass-9"),
            ),
        )
    }

    // ---- 47. all P4 Developer types enter the Full Vault snapshot ----

    @Test
    fun `all three P4 types enter the full vault snapshot`() = runBlocking {
        seedThreeTypes()
        val snapshot = vault.buildConsistentExportSnapshot()
        assertEquals(3, snapshot.developerEntries.size)
        val types = snapshot.developerEntries.map { it::class.java.simpleName }.sorted()
        assertEquals(
            listOf("VaultApiCredential", "VaultGenericSecret", "VaultSshKey"),
            types,
        )
    }

    // ---- 48. package encode/decode preserves the API credential ----

    @Test
    fun `package round trip preserves api credential exactly`() = runBlocking {
        seedThreeTypes()
        val encoded = service.encodeFullVaultExport(pin)
        val decoded = service.decodeWithoutSession(encoded.bytes, pin)

        val api = decoded.snapshot.developerEntries
            .filterIsInstance<VaultApiCredential>()
            .single()
        assertEquals("Stripe API", api.title)
        assertEquals("sk_live_111", api.apiKey)
        assertEquals("api-secret-111", api.apiSecret)
        assertEquals("stripe", api.serviceName)
        assertEquals("alice@example.com", api.accountName)
        assertEquals("prod", api.notes)
    }

    // ---- 49. package preserves the SSH key exact content ----

    @Test
    fun `package round trip preserves ssh key exact content`() = runBlocking {
        seedThreeTypes()
        val encoded = service.encodeFullVaultExport(pin)
        val decoded = service.decodeWithoutSession(encoded.bytes, pin)

        val ssh = decoded.snapshot.developerEntries.filterIsInstance<VaultSshKey>().single()
        assertEquals("deploy-2026", ssh.keyName)
        assertEquals("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIEXAMPLE", ssh.publicKey)
        assertEquals(
            "-----BEGIN OPENSSH PRIVATE KEY-----\nMIIEexample\n-----END OPENSSH PRIVATE KEY-----",
            ssh.privateKey,
        )
        assertEquals("phrase-1", ssh.passphrase)
    }

    // ---- 50. package preserves the Generic Secret label/value pairs ----

    @Test
    fun `package round trip preserves generic secret label value pairs`() = runBlocking {
        seedThreeTypes()
        val encoded = service.encodeFullVaultExport(pin)
        val decoded = service.decodeWithoutSession(encoded.bytes, pin)

        val generic = decoded.snapshot.developerEntries.filterIsInstance<VaultGenericSecret>().single()
        assertEquals("Wi-Fi", generic.title)
        assertEquals(2, generic.fields.size)
        assertEquals("guest-net", generic.fields.first { it.key == "network" }.value)
        assertEquals("wifi-pass-9", generic.fields.first { it.key == "password" }.value)
    }

    // ---- 51. import into an empty vault is an exact round-trip ----

    @Test
    fun `import into empty vault is an exact round trip`() = runBlocking {
        seedThreeTypes()
        val encoded = service.encodeFullVaultExport(pin)
        val decoded = service.decodeWithoutSession(encoded.bytes, pin)

        // New (empty) vault repository.
        val db2 = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val vault2 = VaultRepository(db2, session)
            val outcome = vault2.applySnapshot(decoded.snapshot, "pkg")
            assertTrue(outcome is ImportOutcome.Applied)
            assertEquals(3, (outcome as ImportOutcome.Applied).result.insertedDeveloperEntries)

            val back = vault2.buildDestinationSnapshot().developerEntries
            assertEquals(decoded.snapshot.developerEntries.size, back.size)
            val byStable = back.associateBy { it.stableId }
            val decodedByStable = decoded.snapshot.developerEntries.associateBy { it.stableId }
            assertEquals(decodedByStable.keys, byStable.keys)
            for ((stableId, src) in decodedByStable) {
                val dst = byStable[stableId]!!
                assertEquals(src.title, dst.title)
                assertEquals(src.notes, dst.notes)
                assertEquals(
                    com.rescueauth.v2.export.Canonicalization.developerLogicalFingerprint(src),
                    com.rescueauth.v2.export.Canonicalization.developerLogicalFingerprint(dst),
                )
            }
        } finally {
            db2.close()
        }
    }

    // ---- 52. second import is idempotent ----

    @Test
    fun `second import of the same package is idempotent`() = runBlocking {
        seedThreeTypes()
        val encoded = service.encodeFullVaultExport(pin)
        val decoded = service.decodeWithoutSession(encoded.bytes, pin)

        // Empty destination vault (the import source vault already holds the
        // entries, so apply them into a fresh one).
        val db2 = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val vault2 = VaultRepository(db2, session)

            val outcome1 = vault2.applySnapshot(decoded.snapshot, "pkg-1")
            assertTrue(outcome1 is ImportOutcome.Applied)
            assertEquals(3, (outcome1 as ImportOutcome.Applied).result.insertedDeveloperEntries)

            val outcome2 = vault2.applySnapshot(decoded.snapshot, "pkg-1")
            assertTrue(outcome2 is ImportOutcome.Applied)
            val applied2 = outcome2 as ImportOutcome.Applied
            assertEquals(0, applied2.result.insertedDeveloperEntries)
            assertEquals(3, applied2.result.duplicates)
            assertEquals(3, vault2.buildDestinationSnapshot().developerEntries.size)
        } finally {
            db2.close()
        }
    }

    // ---- 53. same stableId changed payload remains a CONFLICT ----

    @Test
    fun `same stableId with changed logical payload remains a conflict`() = runBlocking {
        val created = devRepo.createApiCredential(
            title = "Stripe", notes = null,
            serviceName = "stripe", accountName = "alice",
            apiKey = "sk_original", apiSecret = "secret-original",
        )
        val encoded = service.encodeFullVaultExport(pin)
        val decoded = service.decodeWithoutSession(encoded.bytes, pin)

        // Edit the local entry so the same stableId now carries a changed payload.
        devRepo.editApiCredential(
            stableId = created.stableId,
            title = "Stripe", notes = null,
            serviceName = "stripe", accountName = "alice",
            apiKey = "sk_changed", apiSecret = "secret-changed",
        )

        val outcome = vault.applySnapshot(decoded.snapshot, "pkg-conflict")
        assertTrue("expected Blocked, got $outcome", outcome is ImportOutcome.Blocked)
        assertEquals(1, (outcome as ImportOutcome.Blocked).result.conflicts)
        // Nothing was written (conflict blocks the whole apply).
        assertEquals(1, vault.buildDestinationSnapshot().developerEntries.size)
    }
}
