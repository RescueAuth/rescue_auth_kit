package com.rescueauth.v2

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.security.FakeStartupAuthPrompt
import com.rescueauth.v2.security.FakeVaultKeyCrypto
import com.rescueauth.v2.security.StartupAuthResult
import com.rescueauth.v2.security.VaultKeyCrypto
import com.rescueauth.v2.security.VaultKeyManager
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the authentication-first startup flow (Issue #50 UX
 * rework).
 *
 * The Vault Keystore operation (create/unwrap) must NEVER run before a
 * successful system authentication; `UserNotAuthenticatedException` must map
 * to a typed AUTH_REQUIRED outcome (not a crash); first-run shows a one-time
 * security intro before prompting; existing-vault launches request
 * authentication automatically; a user cancel finishes the Activity without
 * opening the Vault; and a device with no secure lock shows a blocking state.
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

    // ---- A. First-run: show intro, then (after Enable) request auth ----

    @Test
    fun `first run shows intro and does not prompt until continue`() {
        val crypto = FakeVaultKeyCrypto()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        val prompt = FakeStartupAuthPrompt(availableAuthenticators = 1, nextResult = StartupAuthResult.Success)
        activity.startupAuthPromptFactory = { prompt }

        // Track whether crypto was touched before auth — it must NOT be.
        var wrapCalled = false
        val recordingCrypto = object : VaultKeyCrypto {
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

        controller.setup() // onCreate → onStart → onResume (decides startup mode)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // First-run shows the intro first — the system prompt must NOT be
        // launched before the user taps Enable.
        assertFalse("prompt must not launch before Enable on first run", prompt.promptLaunched)
        assertFalse("crypto must NOT run before auth success", wrapCalled)

        // User taps Enable → authentication is requested.
        activity.onIntroContinue()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("auth prompt must be launched after Enable", prompt.promptLaunched)
        assertFalse("crypto must NOT run before auth success", wrapCalled)

        // Deliver auth success → then (and only then) crypto runs.
        prompt.deliver(StartupAuthResult.Success)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("crypto must run after auth success", wrapCalled)

        teardown(controller)
    }

    // ---- B. First-run: intro → Enable → auth success → createVault ----

    @Test
    fun `first run intro then auth success opens the vault`() {
        val crypto = FakeVaultKeyCrypto()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.sessionManagerFactory = { buildFakeSessionManager(it, crypto, precreatedVault = false) }
        val prompt = FakeStartupAuthPrompt(availableAuthenticators = 1, nextResult = StartupAuthResult.Success)
        activity.startupAuthPromptFactory = { prompt }

        controller.setup()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertFalse("vault must not be open before Enable", activity.sessionManager.sessionState.isUnlocked())
        activity.onIntroContinue()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse("vault must not be open before auth", activity.sessionManager.sessionState.isUnlocked())

        prompt.deliver(StartupAuthResult.Success)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue("vault must be open after auth success", activity.sessionManager.sessionState.isUnlocked())
        teardown(controller)
    }

    // ---- Cancel → Activity finishes, vault NOT opened ----

    @Test
    fun `first run user cancel finishes activity and does not open the vault`() {
        val crypto = FakeVaultKeyCrypto()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.sessionManagerFactory = { buildFakeSessionManager(it, crypto, precreatedVault = false) }
        val prompt = FakeStartupAuthPrompt(availableAuthenticators = 1, nextResult = StartupAuthResult.Cancelled)
        activity.startupAuthPromptFactory = { prompt }

        controller.setup()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        activity.onIntroContinue()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        prompt.deliver(StartupAuthResult.Cancelled)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertFalse("vault must NOT be open after cancel", activity.sessionManager.sessionState.isUnlocked())
        assertNull("vault DB must not be created after cancel", activity.sessionManager.databaseOrNull())
        assertTrue("activity must be finishing after cancel", activity.isFinishing)
        teardown(controller)
    }

    // ---- A2. Intro acknowledgment is persisted independent of Vault creation ----

    @Test
    fun `after enable then cancel, next launch skips intro and requests auth directly`() {
        val crypto = FakeVaultKeyCrypto()

        // First launch: first-run shows the intro, user taps Enable, then
        // cancels system authentication → Activity finishes, Vault NOT created.
        val controller1 = Robolectric.buildActivity(MainActivity::class.java)
        val activity1 = controller1.get()
        activity1.sessionManagerFactory = { buildFakeSessionManager(it, crypto, precreatedVault = false) }
        val prompt1 = FakeStartupAuthPrompt(availableAuthenticators = 1, nextResult = StartupAuthResult.Cancelled)
        activity1.startupAuthPromptFactory = { prompt1 }

        controller1.setup()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        // Intro is shown (Vault still not created).
        assertFalse("intro must gate first launch before Enable", prompt1.promptLaunched)
        activity1.onIntroContinue()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        prompt1.deliver(StartupAuthResult.Cancelled)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("first launch must finish after cancel", activity1.isFinishing)
        assertNull("vault must not be created after cancel", activity1.sessionManager.databaseOrNull())
        teardown(controller1)

        // Second launch: Vault is STILL not created (still first-run), so the
        // adaptive intro re-shows — it appears whenever the Vault is not enabled.
        // The previously-cancelled launch does NOT auto-skip the intro.
        val controller2 = Robolectric.buildActivity(MainActivity::class.java)
        val activity2 = controller2.get()
        activity2.sessionManagerFactory = { buildFakeSessionManager(it, crypto, precreatedVault = false) }
        val prompt2 = FakeStartupAuthPrompt(availableAuthenticators = 1, nextResult = StartupAuthResult.Success)
        activity2.startupAuthPromptFactory = { prompt2 }

        controller2.setup()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // Adaptive intro: because the Vault is still not enabled, the intro gate
        // re-appears and authentication is NOT auto-requested.
        assertFalse("second launch must re-show intro, not auto-prompt", prompt2.promptLaunched)
        assertFalse("vault must still not be open before Enable", activity2.sessionManager.sessionState.isUnlocked())

        // Tapping Enable on the re-shown intro proceeds to authentication.
        activity2.onIntroContinue()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("second launch must request auth after Enable", prompt2.promptLaunched)
        teardown(controller2)
    }

    // ---- C + D. Existing vault: locked session → automatic auth → open ----

    @Test
    fun `existing vault locked session automatically requests auth and opens after success`() {
        val crypto = FakeVaultKeyCrypto()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.sessionManagerFactory = { buildFakeSessionManager(it, crypto, precreatedVault = true) }
        val prompt = FakeStartupAuthPrompt(availableAuthenticators = 1, nextResult = StartupAuthResult.Success)
        activity.startupAuthPromptFactory = { prompt }

        controller.setup()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // Existing vault → must request authentication AUTOMATICALLY (no
        // button tap, no intro).
        assertTrue("existing vault must auto-request auth", prompt.promptLaunched)
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

        // First-run shows the intro first; after Enable, the prompt resolves
        // to Unavailable → blocking state; no crypto ran.
        activity.onIntroContinue()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // The prompt resolves to Unavailable → blocking state; no crypto ran.
        assertFalse("vault must not open without a secure device", activity.sessionManager.sessionState.isUnlocked())
        assertNull("crypto must not run on no-secure-device", crypto.readWrapped())
        teardown(controller)
    }

    // ---- H. Background/foreground re-entry (Issue #70) ----
    //
    // When a BiometricPrompt is showing and the activity goes to background
    // (e.g. the user presses Home), the prompt is dismissed by the system. The
    // startup auth controller must release its internal state so that on the
    // next foreground resume the app can re-request authentication. Before the
    // fix, promptActive stayed true and the next resume never launched a new
    // prompt — the user was stuck on the neutral auth host forever.

    @Test
    fun `background during auth then foreground resumes re-requests auth`() {
        val crypto = FakeVaultKeyCrypto()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.sessionManagerFactory = { buildFakeSessionManager(it, crypto, precreatedVault = true) }
        val prompt = FakeStartupAuthPrompt(availableAuthenticators = 1, nextResult = StartupAuthResult.Success)
        activity.startupAuthPromptFactory = { prompt }

        controller.setup() // onCreate → onStart → onResume
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // Existing vault → auth requested automatically.
        assertTrue("first launch must request auth", prompt.promptLaunched)

        // User presses Home while the prompt is showing → onPause fires and
        // cancels the prompt.
        controller.pause()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("prompt must be cancelled when activity pauses", prompt.cancelCalled)
        // The Activity must NOT be finished because the cancel came from going
        // to background, not a user-initiated cancel.
        assertFalse("activity must not finish on background", activity.isFinishing)

        controller.stop()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // Simulate the 30s auto-lock happening while in background.
        activity.sessionManager.lock()

        // User returns to the app → onStart + onResume must re-request auth.
        controller.start()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        controller.resume()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // The prompt must have been launched again (new tryStart call).
        assertTrue("auth must be re-requested after background/foreground", prompt.promptLaunched)

        // Complete auth successfully.
        prompt.deliver(StartupAuthResult.Success)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("vault must open after re-auth", activity.sessionManager.sessionState.isUnlocked())

        teardown(controller)
    }

    @Test
    fun `background during auth and short return below auto-lock restores shell`() {
        val crypto = FakeVaultKeyCrypto()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.sessionManagerFactory = { buildFakeSessionManager(it, crypto, precreatedVault = true) }
        val prompt = FakeStartupAuthPrompt(availableAuthenticators = 1, nextResult = StartupAuthResult.Success)
        activity.startupAuthPromptFactory = { prompt }

        controller.setup() // onCreate → onStart → onResume
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // Existing vault → auth requested automatically.
        assertTrue("first launch must request auth", prompt.promptLaunched)

        // Complete auth so the session becomes UNLOCKED.
        prompt.deliver(StartupAuthResult.Success)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("vault must open after auth", activity.sessionManager.sessionState.isUnlocked())

        // User presses Home while the app is unlocked.
        controller.pause()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        controller.stop()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // User returns quickly (< 30s) — session was NOT locked. The app shell
        // must be restored, not the auth host.
        controller.start()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        controller.resume()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue("vault must remain open on quick return", activity.sessionManager.sessionState.isUnlocked())
        teardown(controller)
    }

    // ====================================================================
    // Issue #70 — second-launch auth deadlock regression tests.
    // ====================================================================

    /** Idles the Robolectric main looper so lifecycle/coroutine work runs. */
    private fun idle() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    // ---- Test A — exact deadlock: tryStart returns false then retry succeeds ----

    @Test
    fun `existing vault tryStart host not ready then retry opens vault`() {
        val crypto = FakeVaultKeyCrypto()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.sessionManagerFactory = { buildFakeSessionManager(it, crypto, precreatedVault = true) }
        // First launch attempt is rejected (host lifecycle not ready), the
        // scheduler must retry and the second attempt must succeed.
        val prompt = FakeStartupAuthPrompt(
            availableAuthenticators = 1,
            nextResult = StartupAuthResult.Success,
        ).apply { launchResults = listOf(false, true) }
        activity.startupAuthPromptFactory = { prompt }

        controller.setup() // onCreate → onStart → onResume
        idle()

        // The request must NOT have been silently dropped: the scheduler
        // retried after tryStart returned false.
        assertTrue(
            "scheduler must retry after a false tryStart",
            prompt.successfulLaunchCount >= 1,
        )
        assertTrue(
            "a rejected attempt must have been observed",
            prompt.launchAttemptCount >= 2,
        )

        // Auth was re-requested (a prompt is active) — complete it.
        prompt.deliver(StartupAuthResult.Success)
        idle()
        assertTrue("vault must open after retried auth", activity.sessionManager.sessionState.isUnlocked())
        teardown(controller)
    }

    // ---- Test B — repeated relock + foreground always re-requests auth (5x) ----

    @Test
    fun `repeated relock and foreground re-requests auth for five cycles`() {
        val crypto = FakeVaultKeyCrypto()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.sessionManagerFactory = { buildFakeSessionManager(it, crypto, precreatedVault = true) }
        val prompt = FakeStartupAuthPrompt(
            availableAuthenticators = 1,
            nextResult = StartupAuthResult.Success,
        )
        activity.startupAuthPromptFactory = { prompt }

        controller.setup() // initial launch → auto auth
        idle()
        assertTrue("initial launch must request auth", prompt.promptLaunched)
        prompt.deliver(StartupAuthResult.Success)
        idle()
        assertTrue("initial unlock", activity.sessionManager.sessionState.isUnlocked())

        // 5 relock → background → foreground → re-auth cycles.
        repeat(5) { cycle ->
            activity.sessionManager.lock()
            assertFalse("cycle $cycle must lock", activity.sessionManager.sessionState.isUnlocked())

            // Background.
            controller.pause(); idle()
            controller.stop(); idle()

            // Foreground → a brand-new prompt must be launched.
            controller.start(); idle()
            controller.resume(); idle()
            assertTrue("cycle $cycle must re-request auth", prompt.promptLaunched)
            assertTrue(
                "cycle $cycle must not reuse a stale prompt",
                prompt.isPromptActive(),
            )

            prompt.deliver(StartupAuthResult.Success)
            idle()
            assertTrue("cycle $cycle must unlock after auth", activity.sessionManager.sessionState.isUnlocked())
        }

        // 1 initial + 5 relock cycles = 6 successful launches.
        assertEquals("every cycle must really re-request auth", 6, prompt.successfulLaunchCount)
        teardown(controller)
    }

    // ---- Test C — AUTHENTICATING invariant never dead (prompt or pending) ----

    @Test
    fun `authenticating invariant holds and request never dropped after tryStart false`() {
        val crypto = FakeVaultKeyCrypto()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.sessionManagerFactory = { buildFakeSessionManager(it, crypto, precreatedVault = true) }
        // First attempt rejected; the retry succeeds and leaves a prompt active.
        val prompt = FakeStartupAuthPrompt(
            availableAuthenticators = 1,
            nextResult = StartupAuthResult.Success,
        ).apply { launchResults = listOf(false, true) }
        activity.startupAuthPromptFactory = { prompt }

        controller.setup()
        idle()

        // We are (or have just been) in AUTHENTICATING with a rejected first
        // attempt — the invariant must hold: either a prompt is active or a
        // request is pending. It must never be both false.
        assertTrue(
            "AUTHENTICATING must keep prompt active or request pending",
            activity.authenticatingInvariantHolds(),
        )
        assertTrue("request must have been retried", prompt.successfulLaunchCount >= 1)

        prompt.deliver(StartupAuthResult.Success)
        idle()
        assertTrue("vault must open after invariant-preserving retry", activity.sessionManager.sessionState.isUnlocked())
        teardown(controller)
    }

    // ---- Test D — first install (intro → Enable → prompt → create) still works ----

    @Test
    fun `first install intro enable prompt success creates vault via scheduler`() {
        val crypto = FakeVaultKeyCrypto()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.sessionManagerFactory = { buildFakeSessionManager(it, crypto, precreatedVault = false) }
        val prompt = FakeStartupAuthPrompt(
            availableAuthenticators = 1,
            nextResult = StartupAuthResult.Success,
        )
        activity.startupAuthPromptFactory = { prompt }

        controller.setup()
        idle()
        // First-run: intro must gate before any prompt (no auto prompt).
        assertFalse("first run must not prompt before Enable", prompt.promptLaunched)
        assertFalse("vault must not open before Enable", activity.sessionManager.sessionState.isUnlocked())

        activity.onIntroContinue()
        idle()
        assertTrue("prompt must launch after Enable", prompt.promptLaunched)

        prompt.deliver(StartupAuthResult.Success)
        idle()
        assertTrue("vault must be created after auth", activity.sessionManager.sessionState.isUnlocked())
        teardown(controller)
    }

    // ---- Test E — background timeout relock → foreground new prompt, data preserved ----

    @Test
    fun `background timeout relock then foreground re-prompts and preserves vault`() {
        val crypto = FakeVaultKeyCrypto()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.sessionManagerFactory = { buildFakeSessionManager(it, crypto, precreatedVault = true) }
        val prompt = FakeStartupAuthPrompt(
            availableAuthenticators = 1,
            nextResult = StartupAuthResult.Success,
        )
        activity.startupAuthPromptFactory = { prompt }

        controller.setup()
        idle()
        assertTrue("initial auth requested", prompt.promptLaunched)
        prompt.deliver(StartupAuthResult.Success)
        idle()
        assertTrue("initial unlock", activity.sessionManager.sessionState.isUnlocked())

        // Background.
        controller.pause(); idle()
        controller.stop(); idle()
        // Simulate the 30s auto-lock timeout happening while in background.
        activity.sessionManager.lock()
        assertFalse("timeout must lock session", activity.sessionManager.sessionState.isUnlocked())

        // Foreground → a new prompt must be requested automatically.
        controller.start(); idle()
        controller.resume(); idle()
        assertTrue("new prompt must be requested after timeout relock", prompt.promptLaunched)

        prompt.deliver(StartupAuthResult.Success)
        idle()
        assertTrue("vault must reopen after timeout relock", activity.sessionManager.sessionState.isUnlocked())
        // Existing-vault data is preserved: the DB was reopened, not recreated.
        assertNotNull("existing vault data must be preserved", activity.sessionManager.databaseOrNull())
        teardown(controller)
    }
}
