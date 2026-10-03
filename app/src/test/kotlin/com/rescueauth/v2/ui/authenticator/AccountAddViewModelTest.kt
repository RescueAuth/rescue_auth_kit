package com.rescueauth.v2.ui.authenticator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.repository.ProviderAccountRepository
import com.rescueauth.v2.repository.VaultRepository
import com.rescueauth.v2.repository.AuthenticatorRepository
import com.rescueauth.v2.scanner.MigrationTestFixtures
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.ui.model.AccountUi
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One submit writes the selected account and its initial content through real Room repositories. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountAddViewModelTest {
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine
    private lateinit var repo: ProviderAccountRepository
    private lateinit var scope: CoroutineScope
    private lateinit var vm: AccountAddViewModel
    private lateinit var existing: AccountUi

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RescueAuthDatabase::class.java)
            .allowMainThreadQueries().build()
        session = SecureSessionStateMachine().apply { beginAuthentication(); onAuthenticationSuccess() }
        repo = ProviderAccountRepository(VaultRepository(db, session), db, session)
        val account = repo.createProvider("GitHub", "Work")
        existing = AccountUi(account.id, account.serviceName, account.accountName)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        vm = AccountAddViewModel({ repo }, session.state, scope, defaultRecoveryTitleProvider = { "恢复码" })
        vm.setAccounts(listOf(existing))
    }

    @After fun teardown() = runBlocking {
        scope.coroutineContext[Job]?.cancelAndJoin()
        db.close()
    }

    @Test fun nameOnlyCreatesAnEmptyAccountInOneSubmit() = runBlocking {
        vm.begin("GitHub")
        vm.onAccountNameChange("Personal")
        assertTrue(vm.submit())
        val added = db.authAccountDao().findByServiceAndAccount("GitHub", "Personal")!!
        assertTrue(db.totpCredentialDao().listByAccount(added.id).isEmpty())
        assertTrue(db.recoveryCodeSetDao().listByAccount(added.id).isEmpty())
    }

    @Test fun homeStartsWithTheCompleteCodeFormAndCreatesTheFirstServiceAtomically() = runBlocking {
        vm.beginAtHome()
        assertEquals(AccountAddScope.VAULT, vm.formState.value.scope)
        assertEquals(AccountAddContentKind.TOTP, vm.formState.value.kind)
        assertEquals(AddMode.MANUAL, vm.formState.value.totp.mode)
        vm.onProviderChange("New service")
        vm.onAccountNameChange("Personal")
        vm.updateTotp { it.copy(secret = "JBSWY3DPEHPK3PXP") }
        assertTrue(vm.submit())
        val account = db.authAccountDao().findByServiceAndAccount("New service", "Personal")!!
        assertEquals(1, db.totpCredentialDao().listByAccount(account.id).size)
    }

    @Test fun invalidHomeContentDoesNotCreateAServiceOrAccount() = runBlocking {
        vm.beginAtHome()
        vm.onProviderChange("New service")
        vm.onAccountNameChange("Personal")
        vm.updateTotp { it.copy(secret = "invalid!") }
        assertFalse(vm.submit())
        assertEquals(0, db.authAccountDao().countByServiceName("New service"))
    }

    @Test fun aHomeUriFillsItsLabelsAndReusesTheExistingAccount() = runBlocking {
        vm.beginAtHome()
        vm.updateTotp { it.copy(mode = AddMode.PASTE, uri = "otpauth://totp/GitHub:Work?secret=JBSWY3DPEHPK3PXP&issuer=GitHub") }
        assertEquals("GitHub", vm.formState.value.provider)
        assertEquals("Work", vm.formState.value.accountName)
        assertTrue(vm.submit())
        assertEquals(1, db.authAccountDao().count())
        assertEquals(1, db.totpCredentialDao().listByAccount(existing.id).size)
    }

    @Test fun uriUpdatesDoNotOverwriteAnExplicitlyChosenHomeTarget() = runBlocking {
        vm.beginAtHome()
        vm.onProviderChange("GitHub")
        vm.selectAccount(existing)
        vm.updateTotp { it.copy(mode = AddMode.PASTE, uri = "otpauth://totp/Other:Elsewhere?secret=JBSWY3DPEHPK3PXP&issuer=Other") }
        assertEquals("GitHub", vm.formState.value.provider)
        assertEquals("Work", vm.formState.value.accountName)
        assertTrue(vm.submit())
        assertEquals(1, db.authAccountDao().count())
    }

    @Test fun deletingAChosenHomeServiceCannotSilentlyRecreateIt() = runBlocking {
        vm.beginAtHome()
        vm.onProviderChange("GitHub")
        vm.onAccountNameChange("Personal")
        vm.updateTotp { it.copy(secret = "JBSWY3DPEHPK3PXP") }
        repo.deleteProvider("GitHub")
        vm.setAccounts(emptyList())
        assertFalse(vm.submit())
        assertEquals("target_unavailable", vm.formState.value.error)
        assertEquals(0, db.authAccountDao().count())
    }

    @Test fun aHomeServiceCanBeCreatedWithoutSecurityContent() = runBlocking {
        vm.beginAtHome(AccountAddContentKind.NONE)
        vm.onProviderChange("New service")
        vm.onAccountNameChange("Personal")
        assertTrue(vm.submit())
        assertEquals(1, db.authAccountDao().countByServiceName("New service"))
    }

    private fun scannerViewModel() = AuthenticatorViewModel(
        repositoryProvider = { AuthenticatorRepository(VaultRepository(db, session), db, session) },
        sessionState = session.state, clock = AuthenticatorViewModel.Clock { 0L }, scope = scope)

    @Test fun rootMigrationScanKeepsTheExistingPreviewAndDoesNotWriteBeforeConfirmation() = runBlocking {
        vm.beginAtHome()
        val scanner = scannerViewModel()
        var closed = 0
        handleAccountAddScan(MigrationTestFixtures.migrationUri(listOf(MigrationTestFixtures.otpEntry())), vm, scanner) { closed++ }
        assertEquals(1, closed)
        assertFalse(vm.formState.value.visible)
        assertTrue(scanner.migrationState.value.isPreviewVisible)
        assertEquals(1, scanner.migrationState.value.candidates.size)
        assertTrue(db.totpCredentialDao().listByAccount(existing.id).isEmpty())
    }

    @Test fun rootMultiQrMigrationContinuesCollectingThroughTheOriginalAdapter() = runBlocking {
        vm.beginAtHome()
        val scanner = scannerViewModel()
        handleAccountAddScan(MigrationTestFixtures.migrationUri(listOf(MigrationTestFixtures.otpEntry()),
            batchSize = 2, batchIndex = 0, batchId = 11), vm, scanner) {}
        assertTrue(scanner.migrationState.value.scannerVisible)
        assertEquals(1 to 2, scanner.migrationState.value.batchProgress)
        assertFalse(scanner.migrationState.value.isPreviewVisible)
    }

    @Test fun aScopedMigrationScanDoesNotBecomeABulkImport() = runBlocking {
        vm.begin("GitHub", existing)
        val scanner = scannerViewModel()
        handleAccountAddScan(MigrationTestFixtures.migrationUri(listOf(MigrationTestFixtures.otpEntry())), vm, scanner) {}
        assertTrue(vm.formState.value.visible)
        assertEquals("invalid_uri", vm.formState.value.error)
        assertTrue(scanner.migrationState.value.candidates.isEmpty())
    }

    @Test fun newAccountAndRecoveryCodesAreSavedTogetherWithoutNamingASet() = runBlocking {
        vm.begin("GitHub")
        vm.onAccountNameChange("Personal")
        vm.selectKind(AccountAddContentKind.RECOVERY)
        vm.onRecoveryValuesChange("sample-one\nsample-two")
        assertTrue(vm.submit())
        val added = db.authAccountDao().findByServiceAndAccount("GitHub", "Personal")!!
        val set = db.recoveryCodeSetDao().listByAccount(added.id).single()
        assertEquals("恢复码", set.title)
        assertEquals(2, db.recoveryCodeDao().listBySet(set.id).size)
    }

    @Test fun invalidCodeDoesNotLeaveAnEmptyAccount() = runBlocking {
        vm.begin("GitHub")
        vm.onAccountNameChange("Personal")
        vm.selectKind(AccountAddContentKind.TOTP)
        vm.updateTotp { it.copy(secret = "invalid!") }
        assertFalse(vm.submit())
        assertEquals("invalid_secret", vm.formState.value.error)
        assertNull(db.authAccountDao().findByServiceAndAccount("GitHub", "Personal"))
    }

    @Test fun duplicateRecoveryCodesDoNotCreateAnAccount() = runBlocking {
        vm.begin("GitHub")
        vm.onAccountNameChange("Personal")
        vm.selectKind(AccountAddContentKind.RECOVERY)
        vm.onRecoveryValuesChange("sample-one\nsample-one")
        assertFalse(vm.submit())
        assertEquals("duplicate_codes", vm.formState.value.error)
        assertNull(db.authAccountDao().findByServiceAndAccount("GitHub", "Personal"))
    }

    @Test fun duplicateAccountKeepsTheFormOpenForCorrection() = runBlocking {
        vm.begin("GitHub")
        vm.onAccountNameChange("Work")
        assertFalse(vm.submit())
        assertTrue(vm.formState.value.visible)
        assertEquals("duplicate_account", vm.formState.value.error)
        vm.onAccountNameChange("Personal")
        assertTrue(vm.submit())
        assertEquals(2, db.authAccountDao().listByServiceName("GitHub").size)
    }

    @Test fun anExistingAccountGetsNewContentWithoutCreatingAnotherOwner() = runBlocking {
        vm.begin("GitHub", existing)
        vm.selectKind(AccountAddContentKind.RECOVERY)
        vm.onRecoveryValuesChange("sample-one")
        assertTrue(vm.submit())
        assertEquals(1, db.authAccountDao().listByServiceName("GitHub").size)
        assertEquals("恢复码", db.recoveryCodeSetDao().listByAccount(existing.id).single().title)
        vm.begin("GitHub", existing)
        vm.selectKind(AccountAddContentKind.RECOVERY)
        vm.onRecoveryValuesChange("sample-two")
        assertTrue(vm.submit())
        assertEquals(setOf("恢复码", "恢复码 2"), db.recoveryCodeSetDao().listByAccount(existing.id).map { it.title }.toSet())
    }

    @Test fun uriParametersGoToTheExplicitlySelectedAccount() = runBlocking {
        vm.begin("GitHub", existing)
        vm.updateTotp { it.copy(mode = AddMode.PASTE,
            uri = "otpauth://totp/Elsewhere:Other?secret=JBSWY3DPEHPK3PXP&issuer=Elsewhere&algorithm=SHA256&digits=8&period=60") }
        assertTrue(vm.submit())
        val code = db.totpCredentialDao().listByAccount(existing.id).single()
        assertEquals("SHA256", code.algorithm)
        assertEquals(8, code.digits)
        assertEquals(60, code.periodSeconds)
        assertEquals(1, db.authAccountDao().count())
    }

    @Test fun cancelledAndLockedDraftsAreClearedWithoutWrites() = runBlocking {
        vm.begin("GitHub")
        vm.onAccountNameChange("Personal")
        vm.selectKind(AccountAddContentKind.RECOVERY)
        vm.onRecoveryValuesChange("sample-one")
        vm.dismiss()
        assertFalse(vm.formState.value.visible)
        assertEquals("", vm.formState.value.recovery.valuesText)
        vm.begin("GitHub")
        vm.updateTotp { it.copy(secret = "sample-draft") }
        session.lock()
        withTimeout(5_000) { while (vm.formState.value.visible) delay(10) }
        assertEquals("", vm.formState.value.totp.secret)
        vm.onScanned("otpauth://totp/Other?secret=JBSWY3DPEHPK3PXP")
        assertEquals("", vm.formState.value.totp.secret)
        assertEquals(1, db.authAccountDao().count())
    }

    @Test fun repeatedSubmitCannotWriteTwoInitialSets() = runBlocking {
        vm.begin("GitHub", existing)
        vm.selectKind(AccountAddContentKind.RECOVERY)
        vm.onRecoveryValuesChange("sample-one")
        val results = listOf(async(Dispatchers.Default) { vm.submit() }, async(Dispatchers.Default) { vm.submit() }).awaitAll()
        assertEquals(1, results.count { it })
        assertEquals(1, db.recoveryCodeSetDao().listByAccount(existing.id).size)
    }

    @Test fun scopedScanFillsTheSameDraftAndRejectsMigrationPayloads() = runBlocking {
        vm.begin("GitHub", existing)
        vm.onScanned("otpauth://totp/Elsewhere:Other?secret=JBSWY3DPEHPK3PXP&issuer=Elsewhere&algorithm=SHA512&digits=8&period=60")
        assertEquals(existing.id, vm.formState.value.selectedAccountId)
        assertEquals("Work", vm.formState.value.accountName)
        assertEquals("SHA512", vm.formState.value.totp.algorithm)
        assertEquals(60, vm.formState.value.totp.periodSeconds)
        vm.onScanned("otpauth-migration://offline?data=invalid")
        assertEquals("invalid_uri", vm.formState.value.error)
        assertEquals(1, db.authAccountDao().count())
    }

    @Test fun movedRecipientCannotSilentlyReceiveContentUnderTheOldProvider() = runBlocking {
        repo.createProvider("Other service", "Other")
        vm.begin("GitHub", existing)
        vm.selectKind(AccountAddContentKind.RECOVERY)
        vm.onRecoveryValuesChange("sample-one")
        repo.moveAccount(existing.id, "Other service")
        assertFalse(vm.submit())
        assertEquals("target_unavailable", vm.formState.value.error)
        assertTrue(db.recoveryCodeSetDao().listByAccount(existing.id).isEmpty())
    }

    @Test fun failureWritingInitialContentRollsBackTheNewAccount() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_initial_set BEFORE INSERT ON recovery_code_set BEGIN SELECT RAISE(ABORT, 'test write failure'); END")
        vm.begin("GitHub")
        vm.onAccountNameChange("Personal")
        vm.selectKind(AccountAddContentKind.RECOVERY)
        vm.onRecoveryValuesChange("sample-one")
        assertFalse(vm.submit())
        assertNull(db.authAccountDao().findByServiceAndAccount("GitHub", "Personal"))
        assertTrue(db.recoveryCodeSetDao().listByAccount(existing.id).isEmpty())
    }
}
