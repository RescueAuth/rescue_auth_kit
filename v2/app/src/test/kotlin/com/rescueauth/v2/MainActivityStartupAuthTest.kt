package com.rescueauth.v2

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.security.FakeStartupAuthPrompt
import com.rescueauth.v2.security.FakeVaultKeyCrypto
import com.rescueauth.v2.security.StartupAuthResult
import com.rescueauth.v2.security.VaultKeyManager
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the authentication-first startup flow (Issue #50).
 *
 * The Vault Keystore operation (create/unwrap) must NEVER run before a
 * successful system authentication; `UserNotAuthenticatedException` must map
 * to a typed AUTH_REQUIRED outcome (not a crash), user cancel must not open
 * the Vault, and a device with no secure lock must show a blocking state.
 *
 * A fake [SessionManager] (in-memory Room + fake crypto) and a fake
 * [FakeStartupAuthPrompt] are injected so these tests never touch AndroidKeyStore
 * or biometric hardware.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityStartupAuthTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun buildFakeSessionManager(
        activity: MainActivity,
        crypto: FakeVaultKeyCrypto,
        precreatedVault: Boolean,
    ): SessionManager {
        val dbFactory: (Context, ByteArray) -> RescueAuthDatabase = { ctx, _ ->
            Room.inMemoryDatabaseBuilder(ctx, RescueAuthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        }
        val mgr = SessionManager(
            context = activity,
            stateMachine = SecureSessionStateMachine(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            databaseFactory = dbFactory,
            vaultKeyManagerFactory = { VaultKeyManager(crypto) },
        )
        // If we are simulating an existing vault, pre-create it directly via
        // the crypto so a wrapped key exists (without driving the startup UI).
        if (precreatedVault) {
            val freshKey = crypto.let { VaultKeyManager(it).generateAndWrap() }
            freshKey.fill(0)
        }
        return mgr
    }

    /** Tear down the launched activity cleanly. */
    private fun teardown(controller: org.robolectric.android.controller.ActivityController<MainActivity>) {
        controller.pause().stop().destroy()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    // ---- A. First-run: no auth → request authentication, no crash ----

    @Test
    fun `first run requests authentication before any keystore operation`() {
        val crypto = FakeVaultKeyCrypto()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        val prompt = FakeStartupAuthPrompt(availableAuthenticators = 1, nextResult = StartupAuthResult.Success)
        activity.startupAuthPromptFactory = { prompt }

        // Track whether crypto was touched before auth — it must NOT be.
        var wrapCalled = false
        val recordingCrypto = object : com.rescueauth.v2.security.VaultKeyCrypto {
            override fun wrap(vaultKey: ByteArray, persist: (String) -> Unit) {
                wrapCalled = true
                crypto.wrap(vaultKey, persist)
            }
            override fun persistBlob(blob: String) = crypto.persistBlob(blob)
            override fun readWrapped(): String? = crypto.readWrapped()
            override fun unwrap(blob: String): ByteArray = crypto.unwrap(blob)
            override fun reset() = crypto.reset()
            override fun deletePersisted() = crypto.deletePersisted()
        }
        activity.sessionManagerFactory = {
            SessionManager(
                context = it,
                stateMachine = SecureSessionStateMachine(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                databaseFactory = { ctx, _ ->
                    Room.inMemoryDatabaseBuilder(ctx, RescueAuthDatabase::class.java)
                        .allowMainThreadQueries()
                        .build()
                },
                vaultKeyManagerFactory = { VaultKeyManager(recordingCrypto) },
            )
        }

        controller.setup() // onCreate → onStart → onResume (triggers requestAuthentication)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // The system auth prompt must have been requested.
        assertTrue("auth prompt must be launched on first-run startup", prompt.promptLaunched)

        // Deliver auth success → then (and only then) crypto runs.
        assertFalse("crypto must NOT run before auth success", wrapCalled)
        prompt.deliver(StartupAuthResult.Success)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("crypto must run after auth success", wrapCalled)

        teardown(controller)
    }

    // ---- B. First-run: auth success → createVault succeeds (opens) ----

    @Test
    fun `first run auth success opens the vault`() {
        val crypto = FakeVaultKeyCrypto()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.sessionManagerFactory = { buildFakeSessionManager(it, crypto, precreatedVault = false) }
        val prompt = FakeStartupAuthPrompt(availableAuthenticators = 1, nextResult = StartupAuthResult.Success)
        activity.startupAuthPromptFactory = { prompt }

        controller.setup()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertFalse("vault must not be open before auth", activity.sessionManager.sessionState.isUnlocked())
        prompt.deliver(StartupAuthResult.Success)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue("vault must be open after auth success", activity.sessionManager.sessionState.isUnlocked())
        teardown(controller)
    }

    // ---- E. User cancel → do not open vault, no crash ----

    @Test
    fun `first run user cancel does not open the vault and does not crash`() {
        val crypto = FakeVaultKeyCrypto()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.sessionManagerFactory = { buildFakeSessionManager(it, crypto, precreatedVault = false) }
        val prompt = FakeStartupAuthPrompt(availableAuthenticators = 1, nextResult = StartupAuthResult.Cancelled)
        activity.startupAuthPromptFactory = { prompt }

        controller.setup()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        prompt.deliver(StartupAuthResult.Cancelled)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertFalse("vault must NOT be open after cancel", activity.sessionManager.sessionState.isUnlocked())
        assertNull("vault DB must not be created after cancel", activity.sessionManager.databaseOrNull())
        teardown(controller)
    }

    // ---- C + D. Existing vault: locked session → auth → unwrap/open ----

    @Test
    fun `existing vault locked session requests auth and opens after success`() {
        val crypto = FakeVaultKeyCrypto()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.sessionManagerFactory = { buildFakeSessionManager(it, crypto, precreatedVault = true) }
        val prompt = FakeStartupAuthPrompt(availableAuthenticators = 1, nextResult = StartupAuthResult.Success)
        activity.startupAuthPromptFactory = { prompt }

        controller.setup()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // Existing vault → must request authentication, not auto-unlock.
        assertTrue("existing vault must request auth", prompt.promptLaunched)
        assertFalse("vault must not be open before auth", activity.sessionManager.sessionState.isUnlocked())

        prompt.deliver(StartupAuthResult.Success)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue("existing vault must open after auth", activity.sessionManager.sessionState.isUnlocked())
        teardown(controller)
    }

    // ---- G. No secure device credential → blocking state, no crash ----

    @Test
    fun `no secure device credential shows blocking state without crash`() {
        val crypto = FakeVaultKeyCrypto()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.sessionManagerFactory = { buildFakeSessionManager(it, crypto, precreatedVault = false) }
        // null authenticators = no PIN / no strong biometric.
        val prompt = FakeStartupAuthPrompt(availableAuthenticators = null, nextResult = StartupAuthResult.Unavailable)
        activity.startupAuthPromptFactory = { prompt }

        controller.setup()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // The prompt resolves to Unavailable → blocking state; no crypto ran.
        assertFalse("vault must not open without a secure device", activity.sessionManager.sessionState.isUnlocked())
        assertNull("crypto must not run on no-secure-device", crypto.readWrapped())
        teardown(controller)
    }
}
