package com.rescueauth.v2

import android.content.Context
import android.view.WindowManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.security.VaultKeyCrypto
import com.rescueauth.v2.security.VaultKeyManager
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FLAG_SECURE screenshot-protection guard (ADR-0003 §4).
 *
 * The activity must set FLAG_SECURE on its root window so Android blocks
 * screenshots and recent-task previews of sensitive TOTP / recovery screens.
 *
 * This test ACTUALLY LAUNCHES [MainActivity] through Robolectric's activity
 * controller (full onCreate → onStart → onResume lifecycle) and asserts the
 * real window flag — it is NOT a constant-value check. A fake [SessionManager]
 * is injected before [android.app.Activity.onCreate] so the launch exercises
 * the real lifecycle without needing AndroidKeyStore or SQLCipher (neither is
 * available under Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureScreenFlagTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun fakeSessionManager(activity: MainActivity): SessionManager {
        val fakeKeyManager = VaultKeyManager(object : VaultKeyCrypto {
            override fun wrap(vaultKey: ByteArray, persist: (String) -> Unit) {
                persist(java.util.Base64.getEncoder().encodeToString(vaultKey))
            }

            override fun persistBlob(blob: String) {}
            override fun readWrapped(): String? = null
            override fun unwrap(blob: String): ByteArray = ByteArray(32) { it.toByte() }
            override fun reset() {}
            override fun deletePersisted() {}
        })
        return SessionManager(
            context = activity,
            stateMachine = SecureSessionStateMachine(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            databaseFactory = { ctx, _ ->
                Room.inMemoryDatabaseBuilder(ctx, RescueAuthDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            },
            vaultKeyManagerFactory = { fakeKeyManager },
        )
    }

    @Test
    fun mainActivityWindowHasFlagSecure() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        // Inject BEFORE onCreate runs.
        activity.sessionManagerFactory = ::fakeSessionManager
        controller.setup() // onCreate → onStart → onResume

        val flags = activity.window.attributes.flags
        assertTrue(
            "FLAG_SECURE must be set on the MainActivity window (flags=0x" +
                flags.toString(16) + ")",
            flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
        )

        // Tear the activity down so its Compose / background coroutines are
        // cancelled — otherwise the zombie resumed activity keeps the Compose
        // idling system busy for later UI tests in the same JVM.
        controller.pause().stop().destroy()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }
}
