package com.rescueauth.v2.ui.authenticator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.repository.AuthenticatorRepository
import com.rescueauth.v2.repository.VaultRepository
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.totp.TotpCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
 * Focused regression tests for TOTP display (Issue #58 — P1 Release Blocker).
 *
 * Covers the full chain from DB persistence → domain → ViewModel → UiState
 * to verify that TOTP codes are generated and available for display:
 *
 * 1. TOTP entity → UiState contains current code
 * 2. Fixed timestamp → expected 6-digit code
 * 3. Period rollover → code updates
 * 4. Legacy imported TOTP → Authenticator presentation shows code
 * 5. Manual-created TOTP → shows code
 * 6. Multiple accounts → each code visible
 * 7. Multiple providers → grouping correct
 * 8. Click Copy → current code is copied
 * 9. Secret never exposed as UI display text
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TotpDisplayRegressionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine
    private lateinit var repo: AuthenticatorRepository
    private lateinit var vaultRepo: VaultRepository

    private var currentTime = 59L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = SecureSessionStateMachine()
        session.beginAuthentication()
        session.onAuthenticationSuccess()
        vaultRepo = VaultRepository(db, session)
        repo = AuthenticatorRepository(vaultRepo, db, session)
    }

    private var activeScope: CoroutineScope? = null

    @After
    fun tearDown() {
        runBlocking { activeScope?.coroutineContext?.get(Job)?.cancelAndJoin() }
        activeScope = null
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

    // ------------------------------------------------------------------
    // 1. TOTP entity → UiState contains current code
    // ------------------------------------------------------------------

    @Test
    fun `totp entity produces ui state with current code`() = runBlocking {
        val vm = viewModel(newScope())
        awaitState(vm) { !it.loading }

        vm.setMode(AddMode.PASTE)
        vm.onUriChange("otpauth://totp/GitHub:alice%40example.com?secret=JBSWY3DPEHPK3PXP")
        assertTrue(vm.submitAdd())

        awaitState(vm) { it.totpCards.isNotEmpty() }
        val card = vm.uiState.value.totpCards[0]
        assertNotNull(card.currentCode)
        assertTrue(card.currentCode.isNotBlank())
        assertTrue(card.currentCode != "••••••")
    }

    // ------------------------------------------------------------------
    // 2. Fixed timestamp → expected 6-digit code
    // ------------------------------------------------------------------

    @Test
    fun `fixed timestamp produces expected six digit code`() = runBlocking {
        val vm = viewModel(newScope())
        awaitState(vm) { !it.loading }

        vm.setMode(AddMode.PASTE)
        vm.onUriChange("otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP")
        assertTrue(vm.submitAdd())

        // t=59, counter=1, SHA1 6-digit for JBSWY3DPEHPK3PXP = "996554"
        currentTime = 59
        vm.onTick()
        awaitState(vm) { it.totpCards.isNotEmpty() }
        assertEquals("996554", vm.uiState.value.totpCards[0].currentCode)

        // Also verify via TotpCore directly
        val directCode = TotpCore.generate("JBSWY3DPEHPK3PXP", "SHA1", 6, 30, 59)
        assertEquals("996554", directCode)
    }

    // ------------------------------------------------------------------
    // 3. Period rollover → code updates
    // ------------------------------------------------------------------

    @Test
    fun `period rollover updates code`() = runBlocking {
        val vm = viewModel(newScope())
        awaitState(vm) { !it.loading }

        vm.setMode(AddMode.PASTE)
        vm.onUriChange("otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP")
        assertTrue(vm.submitAdd())
        awaitState(vm) { it.totpCards.isNotEmpty() }

        // t=59 → counter=1 → "996554", remaining=1
        currentTime = 59
        vm.onTick()
        awaitState(vm) { it.totpCards[0].remainingSeconds == 1 }
        assertEquals("996554", vm.uiState.value.totpCards[0].currentCode)

        // t=60 → counter=2 → "602287", remaining=30
        currentTime = 60
        vm.onTick()
        awaitState(vm) { it.totpCards[0].remainingSeconds == 30 }
        assertEquals("602287", vm.uiState.value.totpCards[0].currentCode)
    }

    // ------------------------------------------------------------------
    // 4. Legacy imported TOTP → Authenticator presentation shows code
    // ------------------------------------------------------------------

    @Test
    fun `legacy imported totp shows code in authenticator`() = runBlocking {
        val vm = viewModel(newScope())
        awaitState(vm) { !it.loading }

        // Simulate legacy import: insert TOTP directly via repository
        // (same path as MergePlanApplicator but through the Authenticator repo)
        val account = repo.findOrCreateAccount("GitHub", "alice@example.com")
        repo.addTotpCredential(
            accountId = account.id,
            secretBase32 = "JBSWY3DPEHPK3PXP",
            algorithm = "SHA1",
            digits = 6,
            periodSeconds = 30,
        )

        currentTime = 59
        vm.onTick()
        awaitState(vm) { it.totpCards.isNotEmpty() }

        val card = vm.uiState.value.totpCards[0]
        assertEquals("GitHub", card.issuer)
        assertEquals("alice@example.com", card.accountName)
        assertEquals("SHA1", card.algorithm)
        assertEquals(6, card.digits)
        assertEquals(30, card.periodSeconds)
        assertEquals("996554", card.currentCode)

        // Verify the code is also in the providers/accounts structure
        val providerAccount = vm.uiState.value.providers
            .find { it.serviceName == "GitHub" }
            ?.accounts?.find { it.accountName == "alice@example.com" }
        assertNotNull(providerAccount)
        assertTrue(providerAccount!!.totpCredentials.isNotEmpty())
        assertEquals("996554", providerAccount.totpCredentials[0].currentCode)
    }

    // ------------------------------------------------------------------
    // 5. Manual-created TOTP → shows code
    // ------------------------------------------------------------------

    @Test
    fun `manual created totp shows code in authenticator`() = runBlocking {
        val vm = viewModel(newScope())
        awaitState(vm) { !it.loading }

        vm.setMode(AddMode.MANUAL)
        vm.onProviderChange("GitLab")
        vm.onAccountNameChange("bob@example.com")
        vm.onSecretChange("JBSWY3DPEHPK3PXP")
        vm.onDigitsChange(6)
        vm.onPeriodChange(30)
        assertTrue(vm.submitAdd())

        currentTime = 59
        vm.onTick()
        awaitState(vm) { it.totpCards.isNotEmpty() }

        val card = vm.uiState.value.totpCards[0]
        assertEquals("GitLab", card.issuer)
        assertEquals("bob@example.com", card.accountName)
        assertEquals("996554", card.currentCode)

        // Also verify in the providers structure
        val providerAccount = vm.uiState.value.providers
            .find { it.serviceName == "GitLab" }
            ?.accounts?.find { it.accountName == "bob@example.com" }
        assertNotNull(providerAccount)
        assertEquals("996554", providerAccount!!.totpCredentials[0].currentCode)
    }

    // ------------------------------------------------------------------
    // 6. Multiple accounts → each code visible
    // ------------------------------------------------------------------

    @Test
    fun `multiple accounts each show their own code`() = runBlocking {
        val vm = viewModel(newScope())
        awaitState(vm) { !it.loading }

        // Account 1: GitHub / alice
        val acct1 = repo.findOrCreateAccount("GitHub", "alice@example.com")
        repo.addTotpCredential(
            accountId = acct1.id,
            secretBase32 = "JBSWY3DPEHPK3PXP",
            algorithm = "SHA1",
            digits = 6,
            periodSeconds = 30,
        )

        // Account 2: Google / bob (different secret → different code)
        val acct2 = repo.findOrCreateAccount("Google", "bob@example.com")
        repo.addTotpCredential(
            accountId = acct2.id,
            secretBase32 = "4F6VS6KX3UXWY2FQ",
            algorithm = "SHA256",
            digits = 6,
            periodSeconds = 30,
        )

        currentTime = 59
        vm.onTick()
        awaitState(vm) { it.totpCards.size == 2 }

        val card1 = vm.uiState.value.totpCards.find { it.issuer == "GitHub" }!!
        val card2 = vm.uiState.value.totpCards.find { it.issuer == "Google" }!!

        // Different secrets → different codes
        assertEquals("996554", card1.currentCode)
        // Verify the second code via TotpCore directly
        val expectedCode2 = TotpCore.generate("4F6VS6KX3UXWY2FQ", "SHA256", 6, 30, 59)
        assertEquals(expectedCode2, card2.currentCode)
        assertFalse(card1.currentCode == card2.currentCode)

        // Both visible in providers structure
        val githubAccount = vm.uiState.value.providers
            .find { it.serviceName == "GitHub" }
            ?.accounts?.find { it.accountName == "alice@example.com" }
        assertNotNull(githubAccount)
        assertEquals("996554", githubAccount!!.totpCredentials[0].currentCode)

        val googleAccount = vm.uiState.value.providers
            .find { it.serviceName == "Google" }
            ?.accounts?.find { it.accountName == "bob@example.com" }
        assertNotNull(googleAccount)
        assertEquals(expectedCode2, googleAccount!!.totpCredentials[0].currentCode)
    }

    // ------------------------------------------------------------------
    // 7. Multiple providers → grouping correct
    // ------------------------------------------------------------------

    @Test
    fun `multiple providers are grouped correctly`() = runBlocking {
        val vm = viewModel(newScope())
        awaitState(vm) { !it.loading }

        // Two providers, each with one account
        val acct1 = repo.findOrCreateAccount("GitHub", "alice@example.com")
        repo.addTotpCredential(acct1.id, "JBSWY3DPEHPK3PXP", "SHA1", 6, 30)

        val acct2 = repo.findOrCreateAccount("Google", "bob@example.com")
        repo.addTotpCredential(acct2.id, "4F6VS6KX3UXWY2FQ", "SHA256", 6, 30)

        // Same provider, second account
        val acct3 = repo.findOrCreateAccount("GitHub", "charlie@example.com")
        repo.addTotpCredential(acct3.id, "JBSWY3DPEHPK3PXP", "SHA1", 6, 30)

        currentTime = 59
        vm.onTick()
        awaitState(vm) { it.totpCards.size == 3 && it.providers.size == 2 }

        val providers = vm.uiState.value.providers
        val github = providers.find { it.serviceName == "GitHub" }
        val google = providers.find { it.serviceName == "Google" }

        assertNotNull(github)
        assertNotNull(google)
        assertEquals(2, github!!.accounts.size)
        assertEquals(1, google!!.accounts.size)

        // Each account has its TOTP code visible
        github.accounts.forEach { account ->
            assertTrue(account.totpCredentials.isNotEmpty())
            assertNotNull(account.totpCredentials[0].currentCode)
        }
        google.accounts.forEach { account ->
            assertTrue(account.totpCredentials.isNotEmpty())
            assertNotNull(account.totpCredentials[0].currentCode)
        }
    }

    // ------------------------------------------------------------------
    // 8. Click Copy → current code is copied
    // ------------------------------------------------------------------

    @Test
    fun `copy emits event with current code`() = runBlocking {
        val vm = viewModel(newScope())
        awaitState(vm) { !it.loading }

        vm.setMode(AddMode.PASTE)
        vm.onUriChange("otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP")
        assertTrue(vm.submitAdd())

        currentTime = 59
        vm.onTick()
        awaitState(vm) { it.totpCards.isNotEmpty() }

        val card = vm.uiState.value.totpCards[0]
        val expectedCode = card.currentCode
        vm.copyCode(card)

        val event = vm.events.value
        assertTrue(event is AuthenticatorEvent.CopyCode)
        assertEquals(expectedCode, (event as AuthenticatorEvent.CopyCode).code)
    }

    // ------------------------------------------------------------------
    // 9. Secret never exposed as UI display text
    // ------------------------------------------------------------------

    @Test
    fun `secret is never exposed in ui state`() = runBlocking {
        val vm = viewModel(newScope())
        awaitState(vm) { !it.loading }

        val secret = "JBSWY3DPEHPK3PXP"
        vm.setMode(AddMode.PASTE)
        vm.onUriChange("otpauth://totp/GitHub:alice?secret=$secret")
        assertTrue(vm.submitAdd())

        currentTime = 59
        vm.onTick()
        awaitState(vm) { it.totpCards.isNotEmpty() }

        val state = vm.uiState.value

        // The secret must never appear in any UI model field
        state.totpCards.forEach { card ->
            assertFalse(card.issuer.contains(secret))
            assertFalse(card.accountName.contains(secret))
            assertFalse(card.currentCode.contains(secret))
        }

        state.providers.forEach { provider ->
            assertFalse(provider.serviceName.contains(secret))
            provider.accounts.forEach { account ->
                assertFalse(account.providerName.contains(secret))
                assertFalse(account.accountName.contains(secret))
                account.totpCredentials.forEach { totp ->
                    assertFalse(totp.issuer.contains(secret))
                    assertFalse(totp.accountName.contains(secret))
                    assertFalse((totp.currentCode ?: "").contains(secret))
                }
            }
        }

        // The TotpCredentialUi must NOT have a secretBase32 field — verify
        // by checking that the toString() does not contain the secret.
        val totpUi = state.providers.firstOrNull()?.accounts?.firstOrNull()?.totpCredentials?.firstOrNull()
        assertNotNull(totpUi)
        assertFalse(totpUi.toString().contains(secret))
    }

    // ------------------------------------------------------------------
    // 10. Multiple TOTP under same account → all visible
    // ------------------------------------------------------------------

    @Test
    fun `multiple totp under same account are all visible`() = runBlocking {
        val vm = viewModel(newScope())
        awaitState(vm) { !it.loading }

        val account = repo.findOrCreateAccount("GitHub", "alice@example.com")
        // First TOTP
        repo.addTotpCredential(account.id, "JBSWY3DPEHPK3PXP", "SHA1", 6, 30)
        // Second TOTP (different secret)
        repo.addTotpCredential(account.id, "4F6VS6KX3UXWY2FQ", "SHA256", 6, 30)

        currentTime = 59
        vm.onTick()
        awaitState(vm) { it.totpCards.size == 2 }

        // Both codes visible in totpCards
        assertEquals(2, vm.uiState.value.totpCards.size)

        // Both codes visible in providers/accounts structure
        val providerAccount = vm.uiState.value.providers
            .find { it.serviceName == "GitHub" }
            ?.accounts?.find { it.accountName == "alice@example.com" }
        assertNotNull(providerAccount)
        assertEquals(2, providerAccount!!.totpCredentials.size)
        providerAccount.totpCredentials.forEach { totp ->
            assertNotNull(totp.currentCode)
            assertTrue(totp.currentCode!!.isNotBlank())
            assertTrue(totp.currentCode != "••••••")
        }
    }

    // ------------------------------------------------------------------
    // 11. Fixed timestamp code comparison (RFC 6238 vector)
    // ------------------------------------------------------------------

    @Test
    fun `totp code matches rfc 6238 test vector`() {
        // RFC 6238 Appendix B test vectors use the key:
        // SHA1: "12345678901234567890" (ASCII) = GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ in Base32
        // But this is 20 bytes → Base32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        // For t=59 (counter=1): expected = "94287082"
        // We use the standard RFC test secret in Base32
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        val code = TotpCore.generate(secret, "SHA1", 8, 30, 59)
        assertEquals("94287082", code)
    }

    // ------------------------------------------------------------------
    // 12. QR / otpauth regression
    // ------------------------------------------------------------------

    @Test
    fun `otpauth uri creates totp that shows code`() = runBlocking {
        val vm = viewModel(newScope())
        awaitState(vm) { !it.loading }

        vm.setMode(AddMode.PASTE)
        vm.onUriChange("otpauth://totp/GitHub:alice%40example.com?secret=JBSWY3DPEHPK3PXP&algorithm=SHA1&digits=6&period=30")
        assertTrue(vm.submitAdd())

        currentTime = 59
        vm.onTick()
        awaitState(vm) { it.totpCards.isNotEmpty() }

        val card = vm.uiState.value.totpCards[0]
        assertEquals("SHA1", card.algorithm)
        assertEquals(6, card.digits)
        assertEquals(30, card.periodSeconds)
        assertEquals("996554", card.currentCode)
    }

    @Test
    fun `otpauth with sha256 and 8 digits creates totp that shows code`() = runBlocking {
        val vm = viewModel(newScope())
        awaitState(vm) { !it.loading }

        vm.setMode(AddMode.PASTE)
        vm.onUriChange("otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&algorithm=SHA256&digits=8&period=30")
        assertTrue(vm.submitAdd())

        currentTime = 59
        vm.onTick()
        awaitState(vm) { it.totpCards.isNotEmpty() }

        val card = vm.uiState.value.totpCards[0]
        assertEquals("SHA256", card.algorithm)
        assertEquals(8, card.digits)
        // Verify the code is generated (not "••••••")
        assertTrue(card.currentCode.isNotBlank())
        assertTrue(card.currentCode != "••••••")
        // Verify via direct TotpCore call
        val expectedCode = TotpCore.generate("JBSWY3DPEHPK3PXP", "SHA256", 8, 30, 59)
        assertEquals(expectedCode, card.currentCode)
    }
}
