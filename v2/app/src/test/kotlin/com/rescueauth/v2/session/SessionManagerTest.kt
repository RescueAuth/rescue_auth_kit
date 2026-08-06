package com.rescueauth.v2.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SessionManager tests: unlock/lock lifecycle, background timeout auto-lock
 * and timer cancellation. Uses an injectable in-memory Room DB (Robolectric
 * cannot load SQLCipher's JNI .so) and a test dispatcher for the lock timer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testKey: ByteArray = ByteArray(32) { it.toByte() }

    private fun testManager(dispatcher: kotlinx.coroutines.CoroutineDispatcher): SessionManager {
        val dbFactory: (Context, ByteArray) -> RescueAuthDatabase = { ctx, _ ->
            Room.inMemoryDatabaseBuilder(ctx, RescueAuthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        }
        return SessionManager(
            context = context,
            stateMachine = SecureSessionStateMachine(),
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            databaseFactory = dbFactory,
        )
    }

    @Test
    fun `unlock with fresh key then lock closes database`() = runTest {
        val mgr = testManager(StandardTestDispatcher(testScheduler))
        assertTrue(mgr.unlockWithFreshKey(testKey))
        assertNotNull(mgr.databaseOrNull())

        mgr.lock()
        assertNull(mgr.databaseOrNull())
    }

    @Test
    fun `background timeout locks after configured delay`() = runTest {
        val mgr = testManager(StandardTestDispatcher(testScheduler))
        mgr.unlockWithFreshKey(testKey)

        mgr.onAppBackgrounded(lockAfterMillis = 30_000L)
        // Before the timeout, still unlocked.
        advanceTimeBy(20_000L)
        assertNotNull(mgr.databaseOrNull())
        // After 30s, locked and DB closed.
        advanceTimeBy(15_000L)
        assertNull(mgr.databaseOrNull())
    }

    @Test
    fun `foreground cancels the lock timer`() = runTest {
        val mgr = testManager(StandardTestDispatcher(testScheduler))
        mgr.unlockWithFreshKey(testKey)

        mgr.onAppBackgrounded(lockAfterMillis = 30_000L)
        mgr.onAppForegrounded()
        advanceTimeBy(60_000L)
        // Timer was cancelled: still unlocked.
        assertNotNull(mgr.databaseOrNull())
    }

    @Test
    fun `lock after zero millis locks immediately`() {
        val mgr = testManager(Dispatchers.Unconfined)
        mgr.unlockWithFreshKey(testKey)
        mgr.onAppBackgrounded(lockAfterMillis = 0L)
        assertNull(mgr.databaseOrNull())
    }

    @Test
    fun `unlock with fresh key moves state machine to UNLOCKED`() {
        val mgr = testManager(Dispatchers.Unconfined)
        mgr.unlockWithFreshKey(testKey)
        assertTrue(mgr.databaseOrNull() != null)
        mgr.lock()
        assertFalse(mgr.databaseOrNull() != null)
    }
}
