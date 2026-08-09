package com.rescueauth.v2.ui.authenticator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.repository.AuthenticatorRepository
import com.rescueauth.v2.repository.VaultRepository
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Authenticator ViewModel tests: real repository path (in-memory Room), add
 * flow (Paste URI / Manual), countdown tick, copy event, delete + Undo.
 *
 * The ViewModel collects Room flows on a real scope; tests poll the UI state
 * with a timeout because Room emits asynchronously on its query executor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthenticatorViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine
    private lateinit var repo: AuthenticatorRepository

    private var currentTime = 59L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = SecureSessionStateMachine()
        session.beginAuthentication()
        session.onAuthenticationSuccess()
        repo = AuthenticatorRepository(VaultRepository(db, session), db, session)
    }

    private var activeScope: CoroutineScope? = null

    @After
    fun tearDown() {
        activeScope?.cancel()
        db.close()
    }

    private fun viewModel(scope: CoroutineScope): AuthenticatorViewModel {
        activeScope = scope
        return AuthenticatorViewModel(
            repositoryProvider = { repo },
            sessionState = session.state,
            clock = AuthenticatorViewModel.Clock { currentTime },
            scope = scope,
        )
    }

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Polls until [predicate] holds (Room emits asynchronously). */
    private suspend fun awaitState(
        vm: AuthenticatorViewModel,
        predicate: (AuthenticatorUiState) -> Boolean,
    ) {
        withTimeout(5_000L) {
            while (!predicate(vm.uiState.value)) {
                delay(10)
            }
        }
    }

    @Test
    fun `empty state after session unlocked with no data`() = runBlocking {
        val vm = viewModel(newScope())
        awaitState(vm) { !it.loading }
        assertTrue(vm.uiState.value.isEmpty)
    }

    @Test
    fun `add via paste uri shows real code in list`() = runBlocking {
        val vm = viewModel(newScope())
        awaitState(vm) { !it.loading }

        vm.setMode(AddMode.PASTE)
        vm.onUriChange("otpauth://totp/GitHub:alice%40example.com?secret=JBSWY3DPEHPK3PXP")
        assertTrue(vm.submitAdd())

        awaitState(vm) { it.totpCards.isNotEmpty() }
        val card = vm.uiState.value.totpCards[0]
        assertEquals("GitHub", card.issuer)
        assertEquals("alice@example.com", card.accountName)
        assertEquals("SHA1", card.algorithm)
        assertEquals(6, card.digits)
        assertEquals(30, card.periodSeconds)
        // t=59, counter=1, SHA1 6-digit for this secret = "996554" (verified).
        assertEquals("996554", card.currentCode)
    }

    @Test
    fun `add manual entry validates and inserts`() = runBlocking {
        val vm = viewModel(newScope())
        awaitState(vm) { !it.loading }

        vm.setMode(AddMode.MANUAL)
        vm.onProviderChange("GitHub")
        vm.onAccountNameChange("alice@example.com")
        vm.onSecretChange("jbs wy3d-pehp k3pxp")
        vm.onDigitsChange(6)
        vm.onPeriodChange(30)
        assertTrue(vm.submitAdd())

        awaitState(vm) { it.totpCards.isNotEmpty() }
        val persisted = repo.observeTotpCredentials().first()
        assertEquals("JBSWY3DPEHPK3PXP", persisted[0].secretBase32)
        assertEquals("GitHub", vm.uiState.value.totpCards[0].issuer)
    }

    @Test
    fun `manual entry missing provider shows validation error`() = runBlocking {
        val vm = viewModel(newScope())
        awaitState(vm) { !it.loading }

        vm.setMode(AddMode.MANUAL)
        vm.onAccountNameChange("alice@example.com")
        vm.onSecretChange("JBSWY3DPEHPK3PXP")
        assertFalse(vm.submitAdd())
        assertNotNull(vm.formState.value.error)
    }

    @Test
    fun `manual entry invalid secret shows validation error`() = runBlocking {
        val vm = viewModel(newScope())
        awaitState(vm) { !it.loading }

        vm.setMode(AddMode.MANUAL)
        vm.onProviderChange("GitHub")
        vm.onAccountNameChange("alice@example.com")
        vm.onSecretChange("NOT!!BASE32")
        assertFalse(vm.submitAdd())
        assertNotNull(vm.formState.value.error)
    }

    @Test
    fun `invalid otpauth uri shows validation error`() = runBlocking {
        val vm = viewModel(newScope())
        awaitState(vm) { !it.loading }

        vm.setMode(AddMode.PASTE)
        vm.onUriChange("otpauth://totp/GitHub:alice")
        assertFalse(vm.submitAdd())
        assertNotNull(vm.formState.value.error)
    }

    @Test
    fun `countdown updates with shared tick`() = runBlocking {
        val vm = viewModel(newScope())
        awaitState(vm) { !it.loading }
        vm.setMode(AddMode.PASTE)
        vm.onUriChange("otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP")
        assertTrue(vm.submitAdd())
        awaitState(vm) { it.totpCards.isNotEmpty() }

        // t=59 -> within-period = 29 -> remaining 1.
        currentTime = 59
        vm.onTick()
        awaitState(vm) { it.totpCards[0].remainingSeconds == 1 }
        assertEquals("996554", vm.uiState.value.totpCards[0].currentCode)

        // t=60 -> code rotates (counter 2), remaining 30.
        currentTime = 60
        vm.onTick()
        awaitState(vm) { it.totpCards[0].remainingSeconds == 30 }
        assertEquals("602287", vm.uiState.value.totpCards[0].currentCode)
    }

    @Test
    fun `copy emits copy event with real code`() = runBlocking {
        val vm = viewModel(newScope())
        awaitState(vm) { !it.loading }
        vm.setMode(AddMode.PASTE)
        vm.onUriChange("otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP")
        assertTrue(vm.submitAdd())
        awaitState(vm) { it.totpCards.isNotEmpty() }

        val card = vm.uiState.value.totpCards[0]
        vm.copyCode(card)
        val event = vm.events.value
        assertTrue(event is AuthenticatorEvent.CopyCode)
        assertEquals(card.currentCode, (event as AuthenticatorEvent.CopyCode).code)
    }

    @Test
    fun `delete removes card and undo restores with stableId`() = runBlocking {
        val vm = viewModel(newScope())
        awaitState(vm) { !it.loading }
        vm.setMode(AddMode.PASTE)
        vm.onUriChange("otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP")
        assertTrue(vm.submitAdd())
        awaitState(vm) { it.totpCards.isNotEmpty() }

        val card = vm.uiState.value.totpCards[0]
        val stableId = card.stableId

        assertTrue(vm.deleteCard(card))
        awaitState(vm) { it.totpCards.isEmpty() }
        assertTrue(vm.events.value is AuthenticatorEvent.Deleted)

        assertTrue(vm.undoDelete())
        awaitState(vm) { it.totpCards.size == 1 }
        assertEquals(stableId, vm.uiState.value.totpCards[0].stableId)
        assertTrue(vm.events.value is AuthenticatorEvent.Restored)
    }
}
