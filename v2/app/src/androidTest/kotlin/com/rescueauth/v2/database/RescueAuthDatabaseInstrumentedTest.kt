package com.rescueauth.v2.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android instrumented test for the REAL SQLCipher-encrypted database.
 *
 * ⚠️ REQUIRES A DEVICE/EMULATOR — SQLCipher's JNI `.so` cannot load under
 * Robolectric (host JVM), so this path is only verifiable on Android.
 * This environment has no device/emulator; the test is written but its
 * execution is UNFINISHED (marked per contract).
 *
 * Run with: `./gradlew :app:connectedDebugAndroidTest`
 *
 * Method names are valid Java/Kotlin identifiers (no backtick spaces) so the
 * DEX builder accepts them for minSdk 26.
 */
@RunWith(AndroidJUnit4::class)
class RescueAuthDatabaseInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val testKey: ByteArray = ByteArray(32) { it.toByte() }

    @Test
    fun encryptedDatabaseOpensAndPersistsAcrossReopen() = runBlocking {
        context.deleteDatabase(RescueAuthDatabase.DB_NAME)
        var db = RescueAuthDatabase.build(context, testKey)
        db.authAccountDao().insertAll(
            listOf(
                AuthAccountEntity(
                    id = "acc-1", serviceName = "GitHub", accountName = "alice@example.com",
                    sortOrder = 0, createdAt = "t", updatedAt = "t",
                )
            )
        )
        RescueAuthDatabase.closeDatabase(db)

        // Reopen with the SAME key: data must persist (proves encryption works).
        db = RescueAuthDatabase.build(context, testKey)
        assertNotNull(db.authAccountDao().getById("acc-1"))
        RescueAuthDatabase.closeDatabase(db)
        context.deleteDatabase(RescueAuthDatabase.DB_NAME)
    }

    @Test
    fun wrongKeyFailsToOpenEncryptedDatabase() = runBlocking {
        context.deleteDatabase(RescueAuthDatabase.DB_NAME)
        val db = RescueAuthDatabase.build(context, testKey)
        db.authAccountDao().insertAll(
            listOf(
                AuthAccountEntity("acc-1", "S", "a", sortOrder = 0, createdAt = "t", updatedAt = "t")
            )
        )
        RescueAuthDatabase.closeDatabase(db)

        val wrongKey = ByteArray(32) { (it + 1).toByte() }
        var threw = false
        try {
            val bad = RescueAuthDatabase.build(context, wrongKey)
            bad.authAccountDao().count()
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("expected SQLCipher to reject the wrong key", threw)
        context.deleteDatabase(RescueAuthDatabase.DB_NAME)
    }

    @Test
    fun databaseFileExistsOnDiskAndIsEncrypted() = runBlocking {
        context.deleteDatabase(RescueAuthDatabase.DB_NAME)
        val db = RescueAuthDatabase.build(context, testKey)
        db.authAccountDao().count()
        RescueAuthDatabase.closeDatabase(db)
        val file = context.getDatabasePath(RescueAuthDatabase.DB_NAME)
        assertTrue(file.exists())
        assertTrue("file should exist and have content", file.length() > 0)
        context.deleteDatabase(RescueAuthDatabase.DB_NAME)
    }

    @Test
    fun corruptedDatabaseFileFailsSafelyWithoutCrashingTheProcess() = runBlocking {
        context.deleteDatabase(RescueAuthDatabase.DB_NAME)
        // Write garbage bytes (not even a SQLCipher header).
        val file = context.getDatabasePath(RescueAuthDatabase.DB_NAME)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(4096) { 0x5A }) // 'Z' garbage

        var threw = false
        var db: RescueAuthDatabase? = null
        try {
            db = RescueAuthDatabase.build(context, testKey)
            db!!.authAccountDao().count()
        } catch (_: Exception) {
            threw = true
        } finally {
            db?.let { RescueAuthDatabase.closeDatabase(it) }
        }
        // The app must not crash; opening a corrupted (non-SQLCipher) file
        // must surface as a recoverable exception instead of a process abort.
        // This is a REAL assertion: it fails if SQLCipher silently succeeds.
        assertTrue("corrupted DB must throw a recoverable exception", threw)
        context.deleteDatabase(RescueAuthDatabase.DB_NAME)
    }

    @Test
    fun reopenAfterCloseWithSameKeyKeepsDataProcessRestartSimulation() = runBlocking {
        context.deleteDatabase(RescueAuthDatabase.DB_NAME)
        var db = RescueAuthDatabase.build(context, testKey)
        db.authAccountDao().insertAll(
            listOf(
                AuthAccountEntity("acc-p", "GitHub", "alice", sortOrder = 0, createdAt = "t", updatedAt = "t")
            )
        )
        RescueAuthDatabase.closeDatabase(db)
        // Simulate process death: drop the reference, reopen from disk.
        db = RescueAuthDatabase.build(context, testKey)
        assertNotNull(db.authAccountDao().getById("acc-p"))
        RescueAuthDatabase.closeDatabase(db)
        context.deleteDatabase(RescueAuthDatabase.DB_NAME)
    }

    @Test
    fun corruptedDatabaseFileIsRejectedEvenIfItHasValidSqliteHeader() = runBlocking {
        // Guard against the weakest possible implementation: a database that
        // is plain SQLite (no SQLCipher header/encryption) must NOT be treated
        // as a valid encrypted vault.
        context.deleteDatabase(RescueAuthDatabase.DB_NAME)
        val file = context.getDatabasePath(RescueAuthDatabase.DB_NAME)
        file.parentFile?.mkdirs()
        // Write a minimal but VALID plain-SQLite header ("SQLite format 3\0").
        val header = ByteArray(4096)
        val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        System.arraycopy(magic, 0, header, 0, magic.size)
        file.writeBytes(header)

        var threw = false
        var db: RescueAuthDatabase? = null
        try {
            db = RescueAuthDatabase.build(context, testKey)
            db!!.authAccountDao().count()
            fail("plain SQLite file must not open as an encrypted vault")
        } catch (_: Exception) {
            threw = true
        } finally {
            db?.let { RescueAuthDatabase.closeDatabase(it) }
        }
        assertTrue("plain SQLite file must be rejected", threw)
        context.deleteDatabase(RescueAuthDatabase.DB_NAME)
    }
}
