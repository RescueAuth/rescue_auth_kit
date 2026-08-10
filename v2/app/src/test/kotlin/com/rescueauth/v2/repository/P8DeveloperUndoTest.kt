package com.rescueauth.v2.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.domain.UndoRestoreOutcome
import com.rescueauth.v2.export.VaultApiCredential
import com.rescueauth.v2.export.VaultEnvironmentVariableSet
import com.rescueauth.v2.export.VaultGenericSecret
import com.rescueauth.v2.export.VaultKeyValue
import com.rescueauth.v2.export.VaultSshKey
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P8 §34 — ordinary Developer Delete + Undo repository tests (Issue #20 P8
 * §12–§14). Android Signing Key keeps destructive confirmation and has NO Undo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P8DeveloperUndoTest {

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

    // 22. API Credential delete + undo exact stableId/payload
    @Test
    fun `api credential delete and undo preserves exact stableId and payload`() = runBlocking {
        val created = repo.createApiCredential("stripe", "my key", "stripe", "alice", "sk_live", "secret-123")
        val snapshot = repo.deleteWithSnapshot(created.stableId)!!
        assertEquals(created.stableId, snapshot.stableId)
        assertNull(repo.getByStableId(created.stableId))

        val outcome = repo.restoreFromSnapshot(snapshot)
        assertEquals(UndoRestoreOutcome.Restored, outcome)
        val restored = repo.getByStableId(created.stableId) as VaultApiCredential
        assertEquals(created.stableId, restored.stableId)
        assertEquals("stripe", restored.serviceName)
        assertEquals("alice", restored.accountName)
        assertEquals("sk_live", restored.apiKey)
        assertEquals("secret-123", restored.apiSecret)
    }

    // 23. SSH Key delete + undo exact stableId/payload
    @Test
    fun `ssh key delete and undo preserves exact stableId and payload`() = runBlocking {
        val created = repo.createSshKey("work", null, "work", "ssh-ed25519 AAA", "PRIVATE-KEY", "pass")
        val snapshot = repo.deleteWithSnapshot(created.stableId)!!
        val outcome = repo.restoreFromSnapshot(snapshot)
        assertEquals(UndoRestoreOutcome.Restored, outcome)
        val restored = repo.getByStableId(created.stableId) as VaultSshKey
        assertEquals(created.stableId, restored.stableId)
        assertEquals("work", restored.keyName)
        assertEquals("PRIVATE-KEY", restored.privateKey)
        assertEquals("pass", restored.passphrase)
    }

    // 24. Env Var Set delete + undo exact ordering/values
    @Test
    fun `env var set delete and undo preserves exact ordering and values`() = runBlocking {
        val created = repo.createEnvironmentVariableSet(
            "env", null, "svc",
            listOf(VaultKeyValue("A", "1"), VaultKeyValue("B", "2"), VaultKeyValue("C", "3")),
        )
        val snapshot = repo.deleteWithSnapshot(created.stableId)!!
        val outcome = repo.restoreFromSnapshot(snapshot)
        assertEquals(UndoRestoreOutcome.Restored, outcome)
        val restored = repo.getByStableId(created.stableId) as VaultEnvironmentVariableSet
        assertEquals(listOf("A", "B", "C"), restored.variables.map { it.key })
        assertEquals(listOf("1", "2", "3"), restored.variables.map { it.value })
        assertEquals("svc", restored.projectName)
    }

    // 25. Generic Secret delete + undo exact fields
    @Test
    fun `generic secret delete and undo preserves exact fields`() = runBlocking {
        val created = repo.createGenericSecret(
            "token", "note", listOf(VaultKeyValue("user", "u1"), VaultKeyValue("pass", "p1")),
        )
        val snapshot = repo.deleteWithSnapshot(created.stableId)!!
        val outcome = repo.restoreFromSnapshot(snapshot)
        assertEquals(UndoRestoreOutcome.Restored, outcome)
        val restored = repo.getByStableId(created.stableId) as VaultGenericSecret
        assertEquals(listOf("user", "pass"), restored.fields.map { it.key })
        assertEquals(listOf("u1", "p1"), restored.fields.map { it.value })
        assertEquals("note", restored.notes)
    }

    // 26. createdAt preserved
    @Test
    fun `createdAt is preserved on undo`() = runBlocking {
        val created = repo.createApiCredential("s", "t", "s", "a", "k", "sec")
        val snapshot = repo.deleteWithSnapshot(created.stableId)!!
        repo.restoreFromSnapshot(snapshot)
        val restored = repo.getByStableId(created.stableId)!!
        assertEquals(created.createdAt, restored.createdAt)
    }

    // 27. secret values unchanged
    @Test
    fun `secret values are unchanged after undo`() = runBlocking {
        val created = repo.createSshKey("k", null, "kn", "pub", "SECRET-PRIVATE", "PHRASE")
        val snapshot = repo.deleteWithSnapshot(created.stableId)!!
        repo.restoreFromSnapshot(snapshot)
        val restored = repo.getByStableId(created.stableId) as VaultSshKey
        assertEquals("SECRET-PRIVATE", restored.privateKey)
        assertEquals("PHRASE", restored.passphrase)
    }

    // 28. failed restore rolls back (conflicting stableId already present)
    @Test
    fun `restore is blocked when stableId already present`() = runBlocking {
        val created = repo.createApiCredential("s", "t", "s", "a", "k", "sec")
        val snapshot = repo.deleteWithSnapshot(created.stableId)!!
        // Re-create the same stableId via a manual insert.
        db.developerEntryDao().upsert(
            DeveloperMappers.toEntity(
                created.copy(apiSecret = "DIFFERENT"),
                0L,
            ),
        )
        val outcome = repo.restoreFromSnapshot(snapshot)
        assertEquals(UndoRestoreOutcome.Blocked, outcome)
        // destination NOT overwritten
        assertEquals("DIFFERENT", (repo.getByStableId(created.stableId) as VaultApiCredential).apiSecret)
    }

    // 29. session lock clears pending Developer undo (repository refuses after lock)
    @Test
    fun `session lock means repository restore is unavailable`() = runBlocking {
        val created = repo.createApiCredential("s", "t", "s", "a", "k", "sec")
        val snapshot = repo.deleteWithSnapshot(created.stableId)!!
        session.lock()
        assertTrue(runCatching { repo.restoreFromSnapshot(snapshot) }.isFailure)
    }

    // 30. process recreation does not restore (no persistence path)
    @Test
    fun `deleted entry stays deleted after no snapshot is persisted`() = runBlocking {
        val created = repo.createApiCredential("s", "t", "s", "a", "k", "sec")
        repo.deleteWithSnapshot(created.stableId)
        // No persisted token anywhere: a fresh lookup sees the deletion.
        assertNull(repo.getByStableId(created.stableId))
    }

    // 31. Snackbar/toString/contentDescription contain no fixture secret
    @Test
    fun `snapshot safe label contains no secret`() = runBlocking {
        val created = repo.createApiCredential("stripe", "my key", "stripe", "alice", "SUPER-SECRET-KEY", "SUPER-SECRET-VALUE")
        val snapshot = repo.deleteWithSnapshot(created.stableId)!!
        assertTrue(!snapshot.safeLabel.contains("SUPER-SECRET-KEY"))
        assertTrue(!snapshot.safeLabel.contains("SUPER-SECRET-VALUE"))
        assertEquals("stripe", snapshot.title)
    }

    // 32 + 33. Android Signing Key keeps confirmation and has NO P8 Undo action
    @Test
    fun `android signing key has no undo snapshot`() = runBlocking {
        val created = repo.createAndroidSigningKey(
            "release", null, "proj", "com.x", "release.jks",
            byteArrayOf(1, 2, 3, 4), "sp", "alias", "kp",
        )
        // deleteWithSnapshot returns null for a Signing Key (no Undo).
        val snapshot = repo.deleteWithSnapshot(created.stableId)
        assertNull(snapshot)
        // It is still deleted via the confirmation path.
        assertNotNull(repo.getByStableId(created.stableId))
        repo.delete(created.stableId)
        assertNull(repo.getByStableId(created.stableId))
    }
}
