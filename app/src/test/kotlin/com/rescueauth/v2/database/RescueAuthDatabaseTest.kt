package com.rescueauth.v2.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
 * Room schema / DAO lifecycle tests on an IN-MEMORY (unencrypted) database.
 *
 * These tests verify the v1 schema, DAO semantics, cascade deletes and Flow
 * emission. The real SQLCipher-encrypted open path is exercised by the
 * Android instrumented test [RescueAuthDatabaseInstrumentedTest] (needs a
 * device/emulator — SQLCipher's JNI .so cannot load under Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RescueAuthDatabaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `schema is created and empty on fresh open`() = runBlocking {
        assertEquals(0, db.authAccountDao().count())
        assertTrue(db.totpCredentialDao().listAll().isEmpty())
    }

    @Test
    fun `insert account and totp credential then read back`() = runBlocking {
        val account = AuthAccountEntity(
            id = "acc-1",
            serviceName = "GitHub",
            accountName = "alice@example.com",
            sortOrder = 0,
            createdAt = "2024-01-01T00:00:00Z",
            updatedAt = "2024-01-01T00:00:00Z",
            legacySourceId = "legacy-1",
        )
        db.authAccountDao().insertAll(listOf(account))
        db.totpCredentialDao().insertAll(
            listOf(
                TotpCredentialEntity(
                    id = "totp-1",
                    accountId = "acc-1",
                    secretBase32 = "JBSWY3DPEHPK3PXP",
                    algorithm = "SHA1",
                    digits = 6,
                    periodSeconds = 30,
                    createdAt = "2024-01-01T00:00:00Z",
                    legacySourceId = "legacy-totp-1",
                )
            )
        )

        val stored = db.authAccountDao().getById("acc-1")
        assertNotNull(stored)
        assertEquals("GitHub", stored!!.serviceName)
        assertEquals(1, db.totpCredentialDao().listByAccount("acc-1").size)
    }

    @Test
    fun `deleting account cascades to credentials`() = runBlocking {
        db.authAccountDao().insertAll(
            listOf(
                AuthAccountEntity("acc-1", "S", "a@b.c", sortOrder = 0, createdAt = "t", updatedAt = "t")
            )
        )
        db.totpCredentialDao().insertAll(
            listOf(TotpCredentialEntity("t1", "acc-1", "SECRET", "SHA1", 6, 30, "t"))
        )
        db.authAccountDao().deleteById("acc-1")
        assertNull(db.authAccountDao().getById("acc-1"))
        assertTrue(db.totpCredentialDao().listByAccount("acc-1").isEmpty())
    }

    @Test
    fun `recovery code set and codes persist`() = runBlocking {
        db.authAccountDao().insertAll(
            listOf(AuthAccountEntity("acc-1", "GitHub", "alice", sortOrder = 0, createdAt = "t", updatedAt = "t"))
        )
        db.recoveryCodeSetDao().insertAll(
            listOf(RecoveryCodeSetEntity("set-1", "acc-1", "Backup codes", "t"))
        )
        db.recoveryCodeDao().insertAll(
            listOf(
                RecoveryCodeEntity("c1", "set-1", "AAAA-BBBB", "UNUSED", null, 0),
                RecoveryCodeEntity("c2", "set-1", "CCCC-DDDD", "UNUSED", null, 1),
            )
        )
        val codes = db.recoveryCodeDao().listBySet("set-1")
        assertEquals(2, codes.size)
        db.recoveryCodeDao().markUsed("c1", "2024-02-02T00:00:00Z")
        val used = db.recoveryCodeDao().listBySet("set-1").first { it.id == "c1" }
        assertEquals("USED", used.status)
        assertNotNull(used.usedAt)
    }

    @Test
    fun `observe accounts emits changes as a flow`() = runBlocking {
        val flow = db.authAccountDao().observeAll().first()
        assertEquals(0, flow.size)
        db.authAccountDao().insertAll(
            listOf(AuthAccountEntity("acc-1", "S", "a", sortOrder = 0, createdAt = "t", updatedAt = "t"))
        )
        val after = db.authAccountDao().observeAll().first()
        assertEquals(1, after.size)
    }
}
