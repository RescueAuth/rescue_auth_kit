package com.rescueauth.v2.ui.authenticator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.repository.AuthenticatorRepository
import com.rescueauth.v2.repository.VaultRepository
import com.rescueauth.v2.scanner.MigrationTestFixtures
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ViewModel QR-scan + migration import tests (Phase 4 P2).
 *
 * Covers the testable, camera-independent parts: scan-result routing into UI
 * state, single/multi-QR migration collection, preview counts, batch import
 * into the real repository, duplicate skipping and cancel-release semantics.
 * Camera hardware is intentionally not faked on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthenticatorScanMigrationTest {

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

    private suspend fun awaitMigration(
        vm: AuthenticatorViewModel,
        predicate: (MigrationImportUiState) -> Boolean,
    ) {
        withTimeout(5_000L) {
            while (!predicate(vm.migrationState.value)) {
                delay(10)
            }
        }
    }

    @Test
    fun `open scanner sets scannerVisible`() = runBlocking {
        val vm = viewModel(newScope())
        vm.openScanner()
        assertTrue(vm.migrationState.value.scannerVisible)
    }

    @Test
    fun `close scanner releases state`() = runBlocking {
        val vm = viewModel(newScope())
        vm.openScanner()
        vm.closeScanner()
        assertFalse(vm.migrationState.value.scannerVisible)
        assertTrue(vm.migrationState.value.candidates.isEmpty())
    }

    @Test
    fun `normal otpauth scan shows preview with parsed values`() = runBlocking {
        val vm = viewModel(newScope())
        vm.openScanner()
        vm.onQrScanned("otpauth://totp/GitHub:alice%40example.com?secret=JBSWY3DPEHPK3PXP")
        val state = vm.migrationState.value
        assertFalse(state.scannerVisible)
        assertTrue(state.isPreviewVisible)
        assertEquals(1, state.candidates.size)
        assertEquals("GitHub", state.candidates[0].issuer)
        assertEquals("alice@example.com", state.candidates[0].name)
    }

    @Test
    fun `single migration qr shows preview counts`() = runBlocking {
        val vm = viewModel(newScope())
        vm.openScanner()
        vm.onQrScanned(
            MigrationTestFixtures.migrationUri(
                listOf(
                    MigrationTestFixtures.otpEntry(name = "good@x.com", issuer = "GitHub"),
                    MigrationTestFixtures.otpEntry(name = "hotp@x.com", issuer = "X", type = 0),
                )
            )
        )
        val state = vm.migrationState.value
        assertFalse(state.scannerVisible)
        assertTrue(state.isPreviewVisible)
        assertEquals(1, state.candidates.count { it.status == com.rescueauth.v2.migration.MigrationEntryStatus.IMPORTABLE })
        assertEquals(1, state.candidates.count { it.status == com.rescueauth.v2.migration.MigrationEntryStatus.UNSUPPORTED })
    }

    @Test
    fun `multi qr batch progress then complete`() = runBlocking {
        val vm = viewModel(newScope())
        vm.openScanner()
        vm.onQrScanned(
            MigrationTestFixtures.migrationUri(
                listOf(MigrationTestFixtures.otpEntry(name = "a@x.com")),
                batchSize = 2, batchIndex = 0, batchId = 11,
            )
        )
        var state = vm.migrationState.value
        assertTrue(state.scannerVisible)
        assertEquals(1 to 2, state.batchProgress)

        vm.onQrScanned(
            MigrationTestFixtures.migrationUri(
                listOf(MigrationTestFixtures.otpEntry(name = "b@x.com")),
                batchSize = 2, batchIndex = 1, batchId = 11,
            )
        )
        state = vm.migrationState.value
        assertFalse(state.scannerVisible)
        assertTrue(state.isPreviewVisible)
        assertEquals(2, state.candidates.size)
    }

    @Test
    fun `wrong batchId is rejected as scan error and keeps collecting`() = runBlocking {
        val vm = viewModel(newScope())
        vm.openScanner()
        vm.onQrScanned(
            MigrationTestFixtures.migrationUri(
                listOf(MigrationTestFixtures.otpEntry(name = "a@x.com")),
                batchSize = 2, batchIndex = 0, batchId = 1,
            )
        )
        vm.onQrScanned(
            MigrationTestFixtures.migrationUri(
                listOf(MigrationTestFixtures.otpEntry(name = "b@x.com")),
                batchSize = 2, batchIndex = 1, batchId = 999,
            )
        )
        val event = vm.events.value
        assertTrue(event is AuthenticatorEvent.ScanError)
        assertEquals("scan_error_batch_conflict", (event as AuthenticatorEvent.ScanError).messageKey)
        // still collecting
        assertTrue(vm.migrationState.value.scannerVisible)
    }

    @Test
    fun `confirm migration imports into real vault`() = runBlocking {
        val vm = viewModel(newScope())
        vm.openScanner()
        vm.onQrScanned(
            MigrationTestFixtures.migrationUri(
                listOf(
                    MigrationTestFixtures.otpEntry(
                        name = "a@x.com", issuer = "GitHub",
                        secret = "Aaaaaaaa".toByteArray(Charsets.UTF_8),
                    ),
                    MigrationTestFixtures.otpEntry(
                        name = "b@x.com", issuer = "GitLab",
                        secret = "Bbbbbbbb".toByteArray(Charsets.UTF_8),
                    ),
                )
            )
        )
        assertTrue(vm.confirmMigrationImport())
        awaitMigration(vm) { it.result != null }
        val result = vm.migrationState.value.result
        assertEquals(2, result!!.imported)
        assertEquals(0, result.duplicates)

        val creds = repo.observeTotpCredentials().first()
        assertEquals(2, creds.size)
        assertTrue(vm.events.value is AuthenticatorEvent.MigrationImported)
    }

    @Test
    fun `duplicate migration import skips existing`() = runBlocking {
        val vm = viewModel(newScope())
        // First import.
        vm.onQrScanned(
            MigrationTestFixtures.migrationUri(
                listOf(MigrationTestFixtures.otpEntry(name = "a@x.com", issuer = "GitHub"))
            )
        )
        assertTrue(vm.confirmMigrationImport())
        awaitMigration(vm) { it.result != null }

        // Second import of the same secret+params → duplicate.
        vm.dismissMigrationPreview()
        vm.onQrScanned(
            MigrationTestFixtures.migrationUri(
                listOf(MigrationTestFixtures.otpEntry(name = "a@x.com", issuer = "GitHub"))
            )
        )
        assertTrue(vm.confirmMigrationImport())
        awaitMigration(vm) { it.result != null && it.result!!.imported == 0 }
        assertEquals(1, vm.migrationState.value.result!!.duplicates)

        val creds = repo.observeTotpCredentials().first()
        assertEquals(1, creds.size)
    }

    @Test
    fun `not supported qr emits scan error and keeps scanner open`() = runBlocking {
        val vm = viewModel(newScope())
        vm.openScanner()
        vm.onQrScanned("https://example.com")
        assertTrue(vm.migrationState.value.scannerVisible)
        assertTrue(vm.events.value is AuthenticatorEvent.ScanError)
    }

    @Test
    fun `unsupported entry never written`() = runBlocking {
        val vm = viewModel(newScope())
        vm.onQrScanned(
            MigrationTestFixtures.migrationUri(
                listOf(
                    MigrationTestFixtures.otpEntry(name = "hotp@x.com", type = 0),
                    MigrationTestFixtures.otpEntry(name = "good@x.com", issuer = "GitHub"),
                )
            )
        )
        assertTrue(vm.confirmMigrationImport())
        awaitMigration(vm) { it.result != null }
        assertEquals(1, vm.migrationState.value.result!!.imported)
        assertEquals(1, vm.migrationState.value.result!!.unsupported)
        val creds = repo.observeTotpCredentials().first()
        assertEquals(1, creds.size)
        assertEquals("good@x.com", creds[0].let {
            repo.observeAccounts().first().first { a -> a.id == it.accountId }.accountName
        })
    }
}
