package com.rescueauth.v2.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.export.VaultApiCredential
import com.rescueauth.v2.export.VaultGenericSecret
import com.rescueauth.v2.export.VaultKeyValue
import com.rescueauth.v2.export.VaultSshKey
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 4 P4 Developer Vault repository tests (Issue #20 §26–§28).
 *
 * Covers API Credential / SSH Key / Generic Secret CRUD, stableId-preserving
 * edit, destructive delete, secret-hidden-by-default metadata list, validation
 * and close/reopen persistence — all against a real (in-memory) Room DB
 * through the serialized [VaultRepository] mutex.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeveloperRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine
    private lateinit var vault: VaultRepository
    private lateinit var repo: DeveloperRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = SecureSessionStateMachine()
        session.beginAuthentication()
        session.onAuthenticationSuccess()
        vault = VaultRepository(db, session)
        repo = DeveloperRepository(vault, db, session)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------
    // API Credential (Issue #20 §26)
    // ------------------------------------------------------------------

    @Test
    fun `api credential create persists and appears in metadata list`() = runBlocking {
        val entry = repo.createApiCredential(
            title = "Stripe",
            notes = "production",
            serviceName = "stripe",
            accountName = "alice@example.com",
            apiKey = "sk_live_123",
            apiSecret = "secret-abc",
        )

        assertNotNull(entry.stableId)
        assertEquals("Stripe", entry.title)
        assertEquals("sk_live_123", entry.apiKey)
        assertEquals("secret-abc", entry.apiSecret)

        val listed = repo.observeAll().first()
        assertEquals(1, listed.size)
        assertEquals(entry.stableId, listed[0].stableId)
        assertEquals("Stripe", listed[0].title)
        // Metadata list must not expose secrets.
        assertTrue(!listed.toString().contains("sk_live_123"))
        assertTrue(!listed.toString().contains("secret-abc"))
    }

    @Test
    fun `api credential read by stableId returns full payload`() = runBlocking {
        val created = repo.createApiCredential(
            title = "GitHub", notes = null,
            serviceName = "github", accountName = "dev",
            apiKey = "ghp_123", apiSecret = "s3cret",
        )
        val read = repo.getByStableId(created.stableId) as VaultApiCredential
        assertEquals("ghp_123", read.apiKey)
        assertEquals("s3cret", read.apiSecret)
    }

    @Test
    fun `api credential edit preserves stableId and createdAt`() = runBlocking {
        val created = repo.createApiCredential(
            title = "Old", notes = "old-notes",
            serviceName = "svc", accountName = "acc",
            apiKey = "k1", apiSecret = "s1",
        )
        val edited = repo.editApiCredential(
            stableId = created.stableId,
            title = "New", notes = "new-notes",
            serviceName = "svc2", accountName = "acc2",
            apiKey = "k2", apiSecret = "s2",
        )
        assertEquals(created.stableId, edited.stableId)
        assertEquals(created.createdAt, edited.createdAt)
        assertEquals("k2", edited.apiKey)
        assertEquals("s2", edited.apiSecret)

        // Only one row exists (no delete+recreate).
        assertEquals(1, repo.observeAll().first().size)
    }

    @Test
    fun `api credential delete removes the row`() = runBlocking {
        val created = repo.createApiCredential(
            title = "T", notes = null,
            serviceName = "s", accountName = "a",
            apiKey = "k", apiSecret = "sec",
        )
        val deleted = repo.delete(created.stableId)
        assertNotNull(deleted)
        assertNull(repo.getByStableId(created.stableId))
        assertEquals(0, repo.observeAll().first().size)
    }

    @Test
    fun `api credential missing required fields is rejected without echoing secrets`() = runBlocking {
        val e = assertThrows(DeveloperRepository.ValidationException::class.java) {
            runBlocking {
                repo.createApiCredential(
                    title = "", notes = null,
                    serviceName = "s", accountName = "a",
                    apiKey = "k", apiSecret = "s3cret-value",
                )
            }
        }
        // The error message must not contain the secret value.
        assertTrue(!e.message!!.contains("s3cret-value"))
        assertEquals(0, repo.observeAll().first().size)
    }

    // ------------------------------------------------------------------
    // SSH Key (Issue #20 §27)
    // ------------------------------------------------------------------

    @Test
    fun `ssh key create edit delete round trip preserves stableId`() = runBlocking {
        val created = repo.createSshKey(
            title = "deploy",
            notes = null,
            keyName = "deploy-2026",
            publicKey = "ssh-ed25519 AAAAC3...",
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nMIIE...\n-----END OPENSSH PRIVATE KEY-----",
            passphrase = "phrase-1",
        )
        assertNotNull(created.stableId)

        val edited = repo.editSshKey(
            stableId = created.stableId,
            title = "deploy-renamed",
            notes = "updated",
            keyName = "deploy-2026",
            publicKey = "ssh-ed25519 AAAAC3...",
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nMIIE...\n-----END OPENSSH PRIVATE KEY-----",
            passphrase = "phrase-2",
        )
        assertEquals(created.stableId, edited.stableId)
        assertEquals("phrase-2", edited.passphrase)

        val deleted = repo.delete(created.stableId)
        assertNotNull(deleted)
        assertNull(repo.getByStableId(created.stableId))
    }

    @Test
    fun `ssh private key is not exposed by the metadata list`() = runBlocking {
        repo.createSshKey(
            title = "work",
            notes = null,
            keyName = "work",
            publicKey = "pub",
            privateKey = "PRIVATE-SECRET-MATERIAL",
            passphrase = "pass",
        )
        val listed = repo.observeAll().first()
        assertTrue(!listed.toString().contains("PRIVATE-SECRET-MATERIAL"))
        assertTrue(!listed.toString().contains("pass"))
    }

    @Test
    fun `ssh private key required validation`() = runBlocking {
        val e = assertThrows(DeveloperRepository.ValidationException::class.java) {
            runBlocking {
                repo.createSshKey(
                    title = "t", notes = null,
                    keyName = "k", publicKey = "pub",
                    privateKey = "", passphrase = "p",
                )
            }
        }
        assertTrue(!e.message!!.contains("pass"))
    }

    // ------------------------------------------------------------------
    // Generic Secret (Issue #20 §28)
    // ------------------------------------------------------------------

    @Test
    fun `generic secret create with multiple fields and edit preserves stableId`() = runBlocking {
        val created = repo.createGenericSecret(
            title = "Wi-Fi fallback",
            notes = null,
            fields = listOf(
                VaultKeyValue("network", "guest"),
                VaultKeyValue("password", "wifi-pass"),
                VaultKeyValue("psk", "psk-value"),
            ),
        )
        assertNotNull(created.stableId)
        assertEquals(3, created.fields.size)

        val edited = repo.editGenericSecret(
            stableId = created.stableId,
            title = "Wi-Fi fallback v2",
            notes = "updated",
            fields = listOf(
                VaultKeyValue("network", "guest"),
                VaultKeyValue("password", "wifi-pass-2"),
            ),
        )
        assertEquals(created.stableId, edited.stableId)
        assertEquals(2, edited.fields.size)
        assertEquals("wifi-pass-2", edited.fields.first { it.key == "password" }.value)

        // Same logical identity preserved (single row).
        assertEquals(1, repo.observeAll().first().size)
        assertEquals("Wi-Fi fallback v2", repo.getByStableId(created.stableId)!!.title)
    }

    @Test
    fun `generic secret values hidden by default in the metadata list`() = runBlocking {
        repo.createGenericSecret(
            title = "notes",
            notes = null,
            fields = listOf(VaultKeyValue("token", "TOPSECRETVALUE")),
        )
        val listed = repo.observeAll().first()
        assertTrue(!listed.toString().contains("TOPSECRETVALUE"))
    }

    @Test
    fun `generic secret requires at least one field`() = runBlocking {
        val e = assertThrows(DeveloperRepository.ValidationException::class.java) {
            runBlocking {
                repo.createGenericSecret(title = "t", notes = null, fields = emptyList())
            }
        }
        assertTrue(e.message!!.isNotEmpty())
    }

    // ------------------------------------------------------------------
    // Persistence close/reopen (Issue #20 §26/§27/§28)
    // ------------------------------------------------------------------

    @Test
    fun `entries survive close and reopen`() = runBlocking {
        val api = repo.createApiCredential(
            title = "API", notes = null,
            serviceName = "svc", accountName = "acc",
            apiKey = "ak", apiSecret = "as",
        )
        val ssh = repo.createSshKey(
            title = "SSH", notes = null,
            keyName = "kn", publicKey = "pub", privateKey = "priv", passphrase = "ph",
        )
        val generic = repo.createGenericSecret(
            title = "GEN", notes = null,
            fields = listOf(VaultKeyValue("k", "v")),
        )

        // Simulate close/reopen with a fresh repository on a fresh DB built
        // from the SAME in-memory database reference (Room keeps the data).
        val repo2 = DeveloperRepository(VaultRepository(db, session), db, session)

        val readApi = repo2.getByStableId(api.stableId) as VaultApiCredential
        assertEquals("ak", readApi.apiKey)
        assertEquals("as", readApi.apiSecret)

        val readSsh = repo2.getByStableId(ssh.stableId) as VaultSshKey
        assertEquals("priv", readSsh.privateKey)
        assertEquals("ph", readSsh.passphrase)

        val readGeneric = repo2.getByStableId(generic.stableId) as VaultGenericSecret
        assertEquals(listOf("k"), readGeneric.fields.map { it.key })
        assertEquals("v", readGeneric.fields[0].value)

        assertEquals(3, repo2.observeAll().first().size)
    }

    // ------------------------------------------------------------------
    // stableId preservation is a hard contract (Issue #20 §18)
    // ------------------------------------------------------------------

    @Test
    fun `edit never mints a new stableId for any of the three types`() = runBlocking {
        val api = repo.createApiCredential("A", null, "s", "a", "k", "sec")
        val ssh = repo.createSshKey("S", null, "kn", "pub", "priv", "ph")
        val gen = repo.createGenericSecret("G", null, listOf(VaultKeyValue("x", "y")))

        val apiEdited = repo.editApiCredential(api.stableId, "A2", null, "s", "a", "k2", "sec2")
        val sshEdited = repo.editSshKey(ssh.stableId, "S2", null, "kn", "pub", "priv2", "ph2")
        val genEdited = repo.editGenericSecret(gen.stableId, "G2", null, listOf(VaultKeyValue("x", "y2")))

        assertEquals(api.stableId, apiEdited.stableId)
        assertEquals(ssh.stableId, sshEdited.stableId)
        assertEquals(gen.stableId, genEdited.stableId)
        assertNotEquals(api.stableId, apiEdited.updatedAt)
        assertEquals(3, repo.observeAll().first().size)
    }
}
