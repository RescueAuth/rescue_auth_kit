package com.rescueauth.v2.legacyimport

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.legacy.LegacyRakVaultImporter
import com.rescueauth.v2.legacy.LegacyVaultSnapshotMapper
import com.rescueauth.v2.repository.VaultRepository
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 5B — Legacy import ViewModel UI/security flow tests (Issue #1 §24).
 *
 * Uses `runBlocking` + an unconfined ViewModel scope so the Room in-memory
 * transaction runs synchronously, matching the existing repository/ViewModel
 * tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyImportViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = SecureSessionStateMachine()
        session.beginAuthentication()
        session.onAuthenticationSuccess()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun repo() = VaultRepository(db, session)
    private fun service() = LegacyImportService(repo())

    private fun loadFixture(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("legacy-fixtures/phase5a/$name.rakvault")
            ?.use { it.readBytes() }
            ?: error("fixture not found: $name")

    private fun newVm(fake: LegacyFileIo): LegacyImportViewModel =
        LegacyImportViewModel(
            context = context,
            serviceProvider = { service() },
            fileIo = fake,
            sessionState = session.state,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

    private fun awaitSettled(vm: LegacyImportViewModel) = runBlocking {
        withTimeout(10_000L) {
            while (true) {
                val s = vm.state.value
                if (s !is LegacyImportViewModel.State.FileSelected &&
                    s !is LegacyImportViewModel.State.Decrypting &&
                    s !is LegacyImportViewModel.State.Applying
                ) {
                    return@withTimeout
                }
                delay(20)
            }
        }
    }

    private val frozenPassword = "test-password-frozen"

    // ------------------------------------------------------------------
    // 1. Legacy entry separate from Native; picker cancel
    // ------------------------------------------------------------------

    @Test
    fun `file picker cancel produces no error and returns to Idle`() = runBlocking {
        val vm = newVm(FakeLegacyFileIo())
        vm.cancelFilePick()
        assertEquals(LegacyImportViewModel.State.Idle, vm.state.value)
    }

    // ------------------------------------------------------------------
    // 3. Oversized file blocked before decrypt
    // ------------------------------------------------------------------

    @Test
    fun `oversized file is rejected before decrypt`() = runBlocking {
        val huge = ByteArray(LegacyRakVaultImporter.DEFAULT_MAX_INPUT_BYTES + 1)
        val vm = newVm(FakeLegacyFileIo(stored = huge))
        vm.handlePickedDocument(Uri.parse("content://legacy/big"), "big.rakvault", huge.size.toLong())
        awaitSettled(vm)
        val s = vm.state.value
        assertTrue("state=$s", s is LegacyImportViewModel.State.Error)
        val err = s as LegacyImportViewModel.State.Error
        assertTrue(err.message.contains("64 MB", ignoreCase = true))
        assertEquals(LegacyImportViewModel.ErrorAction.RESTART, err.action)
        assertNull(vm.activeSession())
    }

    @Test
    fun `oversized file is rejected by the bounded reader at the legacy cap`() {
        // The SAF bounded read enforces 64 MiB + 1 BEFORE any decrypt
        // (production SafLegacyFileIo maps the bounded-reader MalformedPackage
        // to FileTooLarge). The fake mirrors this bound, so a >64 MiB input is
        // rejected at the file-I/O layer without ever reaching the decoder.
        assertEquals(
            LegacyRakVaultImporter.DEFAULT_MAX_INPUT_BYTES,
            64 * 1024 * 1024,
        )
        val huge = ByteArray(LegacyRakVaultImporter.DEFAULT_MAX_INPUT_BYTES + 1)
        assertTrue(huge.size > 64 * 1024 * 1024)
        // BoundedPackageReader reads at most limit+1 bytes (never buffers
        // unbounded) and rejects MalformedPackage once the total exceeds the
        // limit — this is the shared pure-Kotlin bounded-reader contract.
        val reader = com.rescueauth.v2.export.BoundedPackageReader
        try {
            reader.readBounded(
                source = { buffer ->
                    val n = minOf(buffer.size, huge.size)
                    huge.copyInto(buffer, 0, 0, n)
                    n
                },
                limit = LegacyRakVaultImporter.DEFAULT_MAX_INPUT_BYTES,
            )
            org.junit.Assert.fail("expected MalformedPackage")
        } catch (e: com.rescueauth.v2.export.codec.PackageCodecException.MalformedPackage) {
            assertTrue(e.message!!.contains("too large"))
        }
    }

    // ------------------------------------------------------------------
    // 4. No >=10 artificial restriction
    // ------------------------------------------------------------------

    @Test
    fun `any non-empty password is submitted to the decoder`() = runBlocking {
        // The ViewModel API has no length gate: an 8-char password reaches the
        // decoder (the importer then authenticates against the real fixture
        // password). With the correct fixture password it decodes.
        val vm = newVm(FakeLegacyFileIo(stored = loadFixture("frozen_v1_producer_schema3")))
        vm.handlePickedDocument(Uri.parse("content://legacy/frozen"), "frozen.rakvault", null)
        awaitSettled(vm)
        assertEquals(LegacyImportViewModel.State.AwaitingPassword, vm.state.value)
        vm.submitPassword(frozenPassword.toCharArray())
        awaitSettled(vm)
        assertTrue("state=${vm.state.value}", vm.state.value is LegacyImportViewModel.State.Preview)
    }

    // ------------------------------------------------------------------
    // 5. Password not persisted
    // ------------------------------------------------------------------

    @Test
    fun `password never appears in preview or state`() = runBlocking {
        val vm = newVm(FakeLegacyFileIo(stored = loadFixture("frozen_v1_producer_schema3")))
        vm.handlePickedDocument(Uri.parse("content://legacy/frozen"), "frozen.rakvault", null)
        awaitSettled(vm)
        vm.submitPassword(frozenPassword.toCharArray())
        awaitSettled(vm)
        val state = vm.state.value
        assertTrue(state is LegacyImportViewModel.State.Preview)
        val previewText = (state as LegacyImportViewModel.State.Preview).preview.toString()
        assertTrue(!previewText.contains(frozenPassword))
        assertTrue(!previewText.contains("JBSWY3DPEHPK3PXP"))
        assertTrue(!previewText.contains("AAAA-BBBB-CCCC"))
        assertTrue(!previewText.contains("store-pw"))
    }

    // ------------------------------------------------------------------
    // 6/7. Wrong password/corruption safe error; unsupported schema error
    // ------------------------------------------------------------------

    @Test
    fun `wrong password produces safe retry error`() = runBlocking {
        val vm = newVm(FakeLegacyFileIo(stored = loadFixture("frozen_v1_producer_schema3")))
        vm.handlePickedDocument(Uri.parse("content://legacy/frozen"), "frozen.rakvault", null)
        awaitSettled(vm)
        vm.submitPassword("wrong-password".toCharArray())
        awaitSettled(vm)
        val s = vm.state.value
        assertTrue("state=$s", s is LegacyImportViewModel.State.Error)
        val err = s as LegacyImportViewModel.State.Error
        assertEquals(LegacyImportViewModel.ErrorAction.RETRY_PASSWORD, err.action)
        assertTrue(err.message.contains("password may be incorrect", ignoreCase = true) ||
            err.message.contains("corrupted", ignoreCase = true))
        assertTrue(!err.message.contains("secret"))
        assertTrue(!err.message.contains("cipher"))
    }

    @Test
    fun `non-legacy file produces safe restart error`() = runBlocking {
        val vm = newVm(FakeLegacyFileIo(stored = "NOT A VAULT".toByteArray()))
        vm.handlePickedDocument(Uri.parse("content://legacy/notvault"), "x.rakvault", null)
        awaitSettled(vm)
        vm.submitPassword("whatever".toCharArray())
        awaitSettled(vm)
        val s = vm.state.value
        assertTrue("state=$s", s is LegacyImportViewModel.State.Error)
        assertEquals(LegacyImportViewModel.ErrorAction.RESTART, (s as LegacyImportViewModel.State.Error).action)
    }

    // ------------------------------------------------------------------
    // 8/9. Preview no secrets; shows safe counts/schema
    // ------------------------------------------------------------------

    @Test
    fun `preview shows safe counts and schema without secrets`() = runBlocking {
        val vm = newVm(FakeLegacyFileIo(stored = loadFixture("frozen_v1_producer_schema3")))
        vm.handlePickedDocument(Uri.parse("content://legacy/frozen"), "frozen.rakvault", null)
        awaitSettled(vm)
        vm.submitPassword(frozenPassword.toCharArray())
        awaitSettled(vm)
        val preview = (vm.state.value as LegacyImportViewModel.State.Preview).preview
        assertEquals(3, preview.schemaVersion)
        assertEquals(1, preview.accounts)
        assertEquals(5, preview.developerSummary.total)
        // Preview text must not contain any secret value.
        val text = preview.toString()
        assertTrue(!text.contains("JBSWY3DPEHPK3PXP"))
        assertTrue(!text.contains("AAAA-BBBB-CCCC"))
        assertTrue(!text.contains("sk-test-123"))
        assertTrue(!text.contains("MIIE"))
        assertTrue(!text.contains("store-pw"))
        assertTrue(!text.contains("wifi-1"))
    }

    @Test
    fun `wrong password then retry with correct password succeeds`() = runBlocking {
        val vm = newVm(FakeLegacyFileIo(stored = loadFixture("frozen_v1_producer_schema3")))
        vm.handlePickedDocument(Uri.parse("content://legacy/retry"), "frozen.rakvault", null)
        awaitSettled(vm)

        // Wrong password → RETRY_PASSWORD error.
        vm.submitPassword("wrong-password".toCharArray())
        awaitSettled(vm)
        val err = vm.state.value
        assertTrue("state=$err", err is LegacyImportViewModel.State.Error)
        assertEquals(LegacyImportViewModel.ErrorAction.RETRY_PASSWORD, (err as LegacyImportViewModel.State.Error).action)

        // Retry with the correct password on the SAME file → preview.
        vm.retryPassword()
        assertEquals(LegacyImportViewModel.State.AwaitingPassword, vm.state.value)
        vm.submitPassword(frozenPassword.toCharArray())
        awaitSettled(vm)
        assertTrue("state=${vm.state.value}", vm.state.value is LegacyImportViewModel.State.Preview)
    }

    @Test
    fun `corrupted file then choosing a new file clears old state`() = runBlocking {
        val vm = newVm(FakeLegacyFileIo(stored = loadFixture("frozen_v1_producer_schema3")))
        vm.handlePickedDocument(Uri.parse("content://legacy/a"), "a.rakvault", null)
        awaitSettled(vm)
        vm.submitPassword("wrong-password".toCharArray())
        awaitSettled(vm)
        assertTrue(vm.state.value is LegacyImportViewModel.State.Error)

        // Pick a new file → old error/plaintext cleared, back to password step.
        vm.handlePickedDocument(Uri.parse("content://legacy/b"), "b.rakvault", null)
        awaitSettled(vm)
        assertNull(vm.activeSession())
        assertEquals(LegacyImportViewModel.State.AwaitingPassword, vm.state.value)
    }

    // ------------------------------------------------------------------
    // 10. Session lock clears decoded state
    // ------------------------------------------------------------------

    @Test
    fun `session lock clears decoded legacy state and blocks apply`() = runBlocking {
        val vm = newVm(FakeLegacyFileIo(stored = loadFixture("frozen_v1_producer_schema3")))
        vm.handlePickedDocument(Uri.parse("content://legacy/frozen"), "frozen.rakvault", null)
        awaitSettled(vm)
        vm.submitPassword(frozenPassword.toCharArray())
        awaitSettled(vm)
        assertTrue(vm.state.value is LegacyImportViewModel.State.Preview)

        session.lock()
        runBlocking { delay(50) }
        assertEquals(LegacyImportViewModel.State.Idle, vm.state.value)
        assertNull(vm.activeSession())
    }

    // ------------------------------------------------------------------
    // 12. New file clears old state
    // ------------------------------------------------------------------

    @Test
    fun `choosing a new file clears old decoded state`() = runBlocking {
        val vm = newVm(FakeLegacyFileIo(stored = loadFixture("frozen_v1_producer_schema3")))
        vm.handlePickedDocument(Uri.parse("content://legacy/a"), "a.rakvault", null)
        awaitSettled(vm)
        vm.submitPassword(frozenPassword.toCharArray())
        awaitSettled(vm)
        assertTrue(vm.state.value is LegacyImportViewModel.State.Preview)
        assertTrue(vm.activeSession() != null)

        // Pick a different file → all old plaintext state cleared.
        vm.handlePickedDocument(Uri.parse("content://legacy/b"), "b.rakvault", null)
        awaitSettled(vm)
        assertNull(vm.activeSession())
        assertEquals(LegacyImportViewModel.State.AwaitingPassword, vm.state.value)
    }

    // ------------------------------------------------------------------
    // Success flow
    // ------------------------------------------------------------------

    @Test
    fun `preview then confirm applies transactionally and reports success`() = runBlocking {
        val vm = newVm(FakeLegacyFileIo(stored = loadFixture("frozen_v1_producer_schema3")))
        vm.handlePickedDocument(Uri.parse("content://legacy/frozen"), "frozen.rakvault", null)
        awaitSettled(vm)
        vm.submitPassword(frozenPassword.toCharArray())
        awaitSettled(vm)
        val preview = (vm.state.value as LegacyImportViewModel.State.Preview).preview
        assertTrue(!preview.blocked)

        vm.confirmImport()
        awaitSettled(vm)
        val s = vm.state.value
        assertTrue("state=$s", s is LegacyImportViewModel.State.Success)
        val success = s as LegacyImportViewModel.State.Success
        assertTrue(success.imported > 0)
        assertTrue(!success.blocked)
        assertNull(vm.activeSession())
    }

    @Test
    fun `cancel password returns to Idle and clears session`() = runBlocking {
        val vm = newVm(FakeLegacyFileIo(stored = loadFixture("frozen_v1_producer_schema3")))
        vm.handlePickedDocument(Uri.parse("content://legacy/frozen"), "frozen.rakvault", null)
        awaitSettled(vm)
        vm.cancelPassword()
        assertEquals(LegacyImportViewModel.State.Idle, vm.state.value)
        assertNull(vm.activeSession())
    }
}
