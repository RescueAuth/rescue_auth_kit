package com.rescueauth.v2.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.session.SessionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultAccessTest {
    @Test
    fun `reopening vault replaces every dependent repository and reads new database`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = SessionManager(context, SecureSessionStateMachine(), databaseFactory = { ctx, _ ->
            Room.inMemoryDatabaseBuilder(ctx, RescueAuthDatabase::class.java)
                .allowMainThreadQueries().build()
        })
        VaultAccess.clear()
        VaultAccess.sessionManager = manager
        try {
            assertTrue(manager.unlockWithFreshKey(ByteArray(32)))
            val old = dependencies()
            // Force the original handle to open, so lock really closes its pool.
            assertTrue(VaultAccess.authenticatorRepository()!!.observeAccounts().first().isEmpty())
            manager.lock()
            assertTrue(manager.unlockWithFreshKey(ByteArray(32)))
            val fresh = dependencies()
            old.zip(fresh).forEachIndexed { index, (before, after) ->
                assertNotSame("dependency $index must follow the new database", before, after)
            }
            assertTrue(VaultAccess.authenticatorRepository()!!.observeAccounts().first().isEmpty())
        } finally {
            manager.lock()
            VaultAccess.clear()
        }
    }

    private fun dependencies(): List<Any?> = listOf(
        VaultAccess.vaultRepository(), VaultAccess.authenticatorRepository(),
        VaultAccess.recoveryRepository(), VaultAccess.developerRepository(),
        VaultAccess.providerAccountRepository(), VaultAccess.exportImportService(),
        VaultAccess.legacyImportService(),
    )
}
