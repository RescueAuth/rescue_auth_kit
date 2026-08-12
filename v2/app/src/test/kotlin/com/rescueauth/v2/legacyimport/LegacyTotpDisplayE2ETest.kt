package com.rescueauth.v2.legacyimport

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.repository.AuthenticatorRepository
import com.rescueauth.v2.repository.VaultRepository
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.totp.TotpCore
import com.rescueauth.v2.ui.authenticator.AuthenticatorViewModel
import com.rescueauth.v2.ui.authenticator.AddMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end regression test for the TOTP display blocker (Issue #58).
 *
 * Verifies the full chain: frozen Legacy v1 `.rakvault` fixture → import →
 * DB persistence → AuthenticatorRepository → AuthenticatorViewModel →
 * UiState contains the correct TOTP code at a fixed timestamp.
 *
 * Uses the frozen v1 producer schema3 fixture (TEST-ONLY, no real secrets).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyTotpDisplayE2ETest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine
    private lateinit var vaultRepo: VaultRepository
    private lateinit var authRepo: AuthenticatorRepository

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
        authRepo = AuthenticatorRepository(vaultRepo, db, session)
    }

    private var activeScope: CoroutineScope? = null

    @After
    fun tearDown() {
        runBlocking { activeScope?.coroutineContext?.get(Job)?.cancelAndJoin() }
        activeScope = null
        db.close()
    }

    private fun loadFixture(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("legacy-fixtures/phase5a/$name.rakvault")
            ?.use { it.readBytes() }
            ?: error("fixture not found: $name")

    private suspend fun awaitState(
        vm: AuthenticatorViewModel,
        predicate: (com.rescueauth.v2.ui.authenticator.AuthenticatorUiState) -> Boolean,
    ) {
        withTimeout(5_000L) {
            while (!predicate(vm.uiState.value)) {
                delay(10)
            }
        }
    }

    @Test
    fun `legacy import produces correct totp code in authenticator`() = runBlocking {
        // 1. Import the frozen v1 schema3 fixture
        val svc = LegacyImportService(vaultRepo)
        val bytes = loadFixture("frozen_v1_producer_schema3")
        svc.decodeForPreview(bytes, "test-password-frozen".toCharArray())
        val outcome = svc.confirmImport()
        assertTrue(outcome is com.rescueauth.v2.repository.ImportOutcome.Applied)

        // 2. Verify TOTP is in the database
        val totps = db.totpCredentialDao().listAll()
        assertEquals(1, totps.size)
        val totp = totps[0]
        assertEquals("JBSWY3DPEHPK3PXP", totp.secretBase32)
        assertEquals("SHA1", totp.algorithm)
        assertEquals(6, totp.digits)
        assertEquals(30, totp.periodSeconds)

        // 3. Verify the ViewModel generates the correct code
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        activeScope = scope
        val vm = AuthenticatorViewModel(
            repositoryProvider = { authRepo },
            recoveryRepositoryProvider = {
                com.rescueauth.v2.repository.RecoveryCodeRepository(vaultRepo, db, session)
            },
            sessionState = session.state,
            clock = AuthenticatorViewModel.Clock { currentTime },
            scope = scope,
        )

        currentTime = 59
        awaitState(vm) { it.totpCards.isNotEmpty() }

        val card = vm.uiState.value.totpCards[0]
        assertEquals("GitHub", card.issuer)
        assertEquals("alice@example.com", card.accountName)
        assertEquals("SHA1", card.algorithm)
        assertEquals(6, card.digits)
        assertEquals(30, card.periodSeconds)

        // 4. The v2 generated code must match the expected TOTP code
        val expectedCode = TotpCore.generate("JBSWY3DPEHPK3PXP", "SHA1", 6, 30, 59)
        assertEquals("996554", expectedCode)
        assertEquals(expectedCode, card.currentCode)
        assertTrue(card.currentCode != "••••••")

        // 5. The code is also visible in the providers/accounts structure
        val providerAccount = vm.uiState.value.providers
            .find { it.serviceName == "GitHub" }
            ?.accounts?.find { it.accountName == "alice@example.com" }
        assertTrue(providerAccount != null)
        assertTrue(providerAccount!!.totpCredentials.isNotEmpty())
        assertEquals(expectedCode, providerAccount.totpCredentials[0].currentCode)
    }

    @Test
    fun `legacy import totp code matches at different timestamps`() = runBlocking {
        // Import
        val svc = LegacyImportService(vaultRepo)
        val bytes = loadFixture("frozen_v1_producer_schema3")
        svc.decodeForPreview(bytes, "test-password-frozen".toCharArray())
        svc.confirmImport()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        activeScope = scope
        val vm = AuthenticatorViewModel(
            repositoryProvider = { authRepo },
            recoveryRepositoryProvider = {
                com.rescueauth.v2.repository.RecoveryCodeRepository(vaultRepo, db, session)
            },
            sessionState = session.state,
            clock = AuthenticatorViewModel.Clock { currentTime },
            scope = scope,
        )

        // t=59 → counter=1 → "996554"
        currentTime = 59
        vm.onTick()
        awaitState(vm) { it.totpCards.isNotEmpty() }
        assertEquals("996554", vm.uiState.value.totpCards[0].currentCode)

        // t=60 → counter=2 → "602287"
        currentTime = 60
        vm.onTick()
        awaitState(vm) { it.totpCards[0].remainingSeconds == 30 }
        assertEquals("602287", vm.uiState.value.totpCards[0].currentCode)

        // Verify the code in providers structure also updated
        val providerAccount = vm.uiState.value.providers
            .find { it.serviceName == "GitHub" }
            ?.accounts?.find { it.accountName == "alice@example.com" }
        assertEquals("602287", providerAccount!!.totpCredentials[0].currentCode)
    }
}
