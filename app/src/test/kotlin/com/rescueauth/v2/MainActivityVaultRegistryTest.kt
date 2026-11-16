package com.rescueauth.v2

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.repository.VaultAccess
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for the process-wide [VaultAccess] registry ownership.
 *
 * Reported defect: after unlocking, the app occasionally rendered the shell
 * with an EMPTY vault; force-quitting and relaunching fixed it. The shell is
 * shown from [MainActivity]'s own session state, while its data comes from
 * [VaultAccess]. When a previous Activity instance tears down AFTER a newer
 * instance has already registered its SessionManager (user cancels the unlock
 * prompt, the Activity finishes, and the launcher is tapped again before
 * `onDestroy` runs), an unconditional `clear()` unregisters the CURRENT session.
 * The shell then falls back to a throwaway state machine that never reaches
 * UNLOCKED, so every repository accessor returns null and the UI stays empty
 * until the process is restarted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityVaultRegistryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun buildSessionManager(activity: Context): SessionManager = SessionManager(
        context = activity,
        stateMachine = SecureSessionStateMachine(),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        databaseFactory = { ctx, _ ->
            Room.inMemoryDatabaseBuilder(ctx, RescueAuthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        },
    )

    private fun idle() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun disposeComposition(activity: MainActivity) {
        val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        for (index in 0 until content.childCount) {
            (content.getChildAt(index) as? androidx.compose.ui.platform.ComposeView)?.disposeComposition()
        }
        idle()
    }

    @Test
    fun `stale activity teardown must not unregister the current vault session`() {
        VaultAccess.clear()

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val staleActivity = controller.get()
        staleActivity.sessionManagerFactory = { buildSessionManager(it) }

        controller.create().start().resume()
        idle()

        // A newer Activity instance has already registered its own session
        // (the user relaunched while this instance was still tearing down).
        val currentSession = buildSessionManager(context)
        VaultAccess.sessionManager = currentSession

        // The stale instance now finishes its teardown.
        disposeComposition(staleActivity)
        controller.pause().stop().destroy()
        idle()

        assertSame(
            "stale teardown must leave the current session registered",
            currentSession,
            VaultAccess.sessionManager,
        )

        VaultAccess.clear()
    }
}
