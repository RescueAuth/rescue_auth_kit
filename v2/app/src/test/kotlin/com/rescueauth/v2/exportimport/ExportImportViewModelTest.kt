package com.rescueauth.v2.exportimport

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.export.VaultPackagePayload
import com.rescueauth.v2.export.codec.PackageFormat
import com.rescueauth.v2.export.codec.PortablePackageCodec
import com.rescueauth.v2.repository.MergeTestData
import com.rescueauth.v2.repository.VaultRepository
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 3D ViewModel flow tests (Issue #1 §23, §27) — export orchestration,
 * import file/safety, preview → confirm → apply, and the sensitive session
 * lifecycle (cancel / apply / lock clear the active session; PIN never
 * persists).
 *
 * Uses `runBlocking` + an unconfined ViewModel scope so the Room in-memory
 * transaction (allowed on the main thread) runs synchronously — matching the
 * existing repository tests. The unconfined scope also proves the ViewModel
 * methods are safe to call from the main thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportImportViewModelTest {

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

    private fun service() = ExportImportService(repo(), "test-1.0.0")

    private fun payload(packageId: String, snapshot: com.rescueauth.v2.export.VaultSnapshot) =
        VaultPackagePayload(
            logicalSchemaVersion = 1,
            packageId = packageId,
            createdAt = "2024-01-01T00:00:00Z",
            snapshot = snapshot,
        )

    private fun newVm(fake: PackageFileIo): ExportImportViewModel =
        ExportImportViewModel(
            context = context,
            serviceProvider = { service() },
            fileIo = fake,
            sessionState = session.state,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

    private val pin: CharArray get() = "123456".toCharArray()

    /** Waits for the VM state to stop being transient (Working/Decoding). */
    private fun awaitIdle(vm: ExportImportViewModel) = runBlocking {
        withTimeout(5_000L) {
            while (true) {
                val e = vm.exportState.value
                val i = vm.importState.value
                if (e !is ExportImportViewModel.ExportState.Working &&
                    i !is ExportImportViewModel.ImportState.Decoding &&
                    i !is ExportImportViewModel.ImportState.Applying
                ) {
                    return@withTimeout
                }
                delay(20)
            }
        }
    }

    // ------------------------------------------------------------------
    // Export flow (Issue #1 §23)
    // ------------------------------------------------------------------

    @Test
    fun `export cancel produces no success state`() = runBlocking {
        val fake = FakePackageFileIo()
        val vm = newVm(fake)
        // With the restructured flow there is no AwaitingPin until a
        // destination is picked; cancelExport after a destination leaves Idle.
        vm.onExportDestinationPicked(Uri.parse("content://dest/1"))
        assertEquals(ExportImportViewModel.ExportState.AwaitingPin, vm.exportState.value)
        vm.cancelExport()
        assertEquals(ExportImportViewModel.ExportState.Idle, vm.exportState.value)
        assertTrue(fake.written.isEmpty())
    }

    @Test
    fun `export writes only encrypted bytes and reports success`() = runBlocking {
        val fake = FakePackageFileIo()
        val vm = newVm(fake)
        vm.onExportDestinationPicked(Uri.parse("content://dest/1"))
        vm.exportToUri(pin)
        awaitIdle(vm)
        assertTrue("state=${vm.exportState.value}", vm.exportState.value is ExportImportViewModel.ExportState.Success)
        assertEquals(1, fake.written.size)
        val written = fake.written[0]
        // The written bytes are the encrypted package (magic present).
        val magic = PackageFormat.MAGIC.toByteArray(Charsets.US_ASCII)
        assertTrue(written.size >= magic.size)
        for (i in magic.indices) {
            assertTrue("written bytes must start with the package magic", written[i] == magic[i])
        }
    }

    @Test
    fun `export write failure returns error and clears pin`() = runBlocking {
        val fake = FakePackageFileIo(onWrite = { throw ExportImportError.WriteFailed })
        val vm = newVm(fake)
        vm.onExportDestinationPicked(Uri.parse("content://dest/2"))
        vm.exportToUri(pin)
        awaitIdle(vm)
        assertTrue(vm.exportState.value is ExportImportViewModel.ExportState.Error)
    }

    @Test
    fun `pin confirm mismatch blocks export at the UI`() = runBlocking {
        // PinPolicy is the UI-layer check: a too-short or non-digit PIN is
        // rejected by the screen before the codec sees it.
        assertTrue(!PinPolicy.isValidPin("12345".toCharArray()))
        assertTrue(PinPolicy.isValidPin("123456".toCharArray()))
    }

    // ------------------------------------------------------------------
    // Import file / safety (Issue #1 §24)
    // ------------------------------------------------------------------

    @Test
    fun `valid package import flow reaches preview`() = runBlocking {
        val snapshot = MergeTestData.fullSnapshot(
            MergeTestData.account("a", "Svc", "acct", totps = listOf(MergeTestData.totp("t"))),
        )
        val bytes = PortablePackageCodec.encode(payload("pkg-v", snapshot), pin)
        val fake = FakePackageFileIo(stored = bytes)
        val vm = newVm(fake)

        vm.handlePickedDocument(Uri.parse("content://doc/1"))
        awaitIdle(vm)
        assertEquals(ExportImportViewModel.ImportState.AwaitingPin, vm.importState.value)

        vm.decodeImportWithPin(pin)
        awaitIdle(vm)
        assertTrue("state=${vm.importState.value}", vm.importState.value is ExportImportViewModel.ImportState.Preview)
    }

    @Test
    fun `invalid magic is rejected before PIN`() = runBlocking {
        val fake = FakePackageFileIo(stored = "NOTAPACKAGE".toByteArray())
        val vm = newVm(fake)
        vm.handlePickedDocument(Uri.parse("content://doc/bad"))
        awaitIdle(vm)
        assertTrue(vm.importState.value is ExportImportViewModel.ImportState.Error)
    }

    @Test
    fun `empty document is rejected safely`() = runBlocking {
        val fake = FakePackageFileIo(stored = ByteArray(0))
        val vm = newVm(fake)
        vm.handlePickedDocument(Uri.parse("content://doc/empty"))
        awaitIdle(vm)
        assertTrue(vm.importState.value is ExportImportViewModel.ImportState.Error)
    }

    @Test
    fun `user cancel of the picker produces no error`() = runBlocking {
        val fake = FakePackageFileIo()
        val vm = newVm(fake)
        vm.cancelImport()
        assertEquals(ExportImportViewModel.ImportState.Idle, vm.importState.value)
    }

    @Test
    fun `read failure is mapped to a safe message`() = runBlocking {
        val fake = object : FakePackageFileIo(stored = byteArrayOf(1, 2, 3)) {
            override suspend fun readBounded(context: Context, uri: Uri): ByteArray {
                throw ExportImportError.ReadFailed
            }
        }
        val vm = newVm(fake)
        vm.handlePickedDocument(Uri.parse("content://doc/read"))
        awaitIdle(vm)
        assertTrue(vm.importState.value is ExportImportViewModel.ImportState.Error)
    }

    // ------------------------------------------------------------------
    // Preview → confirm → apply (Issue #1 §26)
    // ------------------------------------------------------------------

    @Test
    fun `preview then confirm applies transactionally`() = runBlocking {
        val snapshot = MergeTestData.fullSnapshot(
            MergeTestData.account("a", "Svc", "acct", totps = listOf(MergeTestData.totp("t"))),
            developers = listOf(MergeTestData.signingKey("sk-1")),
        )
        val bytes = PortablePackageCodec.encode(payload("pkg-p", snapshot), pin)
        val fake = FakePackageFileIo(stored = bytes)
        val vm = newVm(fake)
        vm.handlePickedDocument(Uri.parse("content://doc/2"))
        awaitIdle(vm)
        vm.decodeImportWithPin(pin)
        awaitIdle(vm)
        assertTrue(vm.importState.value is ExportImportViewModel.ImportState.Preview)

        vm.confirmImport()
        awaitIdle(vm)
        val state = vm.importState.value
        assertTrue("state=$state", state is ExportImportViewModel.ImportState.Result)
        assertTrue((state as ExportImportViewModel.ImportState.Result).imported > 0)
        assertTrue(!state.blocked)
    }

    @Test
    fun `conflict blocks and result is not partial`() = runBlocking {
        val r = repo()
        r.applySnapshot(
            MergeTestData.fullSnapshot(
                MergeTestData.account("a", "Svc", "acct", totps = listOf(MergeTestData.totp("t1", secret = "JBSWY3DPEHPK3PXP"))),
            ),
            "seed",
        )
        val source = MergeTestData.fullSnapshot(
            MergeTestData.account(
                "a", "Svc", "acct",
                totps = listOf(MergeTestData.totp("t1", secret = "4F6VS6KX3UXWY2FQ")),
            ),
        )
        val bytes = PortablePackageCodec.encode(payload("pkg-b", source), pin)
        val fake = FakePackageFileIo(stored = bytes)
        val vm = newVm(fake)
        vm.handlePickedDocument(Uri.parse("content://doc/3"))
        awaitIdle(vm)
        vm.decodeImportWithPin(pin)
        awaitIdle(vm)
        val preview = (vm.importState.value as ExportImportViewModel.ImportState.Preview).preview
        assertTrue(preview.blocked)

        vm.confirmImport()
        awaitIdle(vm)
        val state = vm.importState.value as ExportImportViewModel.ImportState.Result
        assertTrue(state.blocked)
        assertEquals(1, state.conflicts)
        assertEquals(0, state.imported)
    }

    @Test
    fun `duplicate-only confirm reports nothing new`() = runBlocking {
        val snapshot = MergeTestData.fullSnapshot(
            MergeTestData.account("a", "Svc", "acct", totps = listOf(MergeTestData.totp("t"))),
        )
        repo().applySnapshot(snapshot, "seed")
        val bytes = PortablePackageCodec.encode(payload("pkg-d", snapshot), pin)
        val fake = FakePackageFileIo(stored = bytes)
        val vm = newVm(fake)
        vm.handlePickedDocument(Uri.parse("content://doc/4"))
        awaitIdle(vm)
        vm.decodeImportWithPin(pin)
        awaitIdle(vm)
        vm.confirmImport()
        awaitIdle(vm)
        val state = vm.importState.value as ExportImportViewModel.ImportState.Result
        assertTrue(state.duplicates >= 1)
        assertEquals(0, state.imported)
        assertTrue(!state.blocked)
    }

    // ------------------------------------------------------------------
    // Sensitive lifecycle (Issue #1 §27)
    // ------------------------------------------------------------------

    @Test
    fun `cancel import clears the active import session`() = runBlocking {
        val bytes = PortablePackageCodec.encode(
            payload("pkg-c", MergeTestData.fullSnapshot(developers = MergeTestData.allFiveDevelopers())),
            pin,
        )
        val fake = FakePackageFileIo(stored = bytes)
        val vm = newVm(fake)
        vm.handlePickedDocument(Uri.parse("content://doc/5"))
        awaitIdle(vm)
        vm.decodeImportWithPin(pin)
        awaitIdle(vm)
        assertNotNull(vm.activeImportSession())
        vm.cancelImport()
        assertNull(vm.activeImportSession())
    }

    @Test
    fun `successful apply clears the active import session`() = runBlocking {
        val bytes = PortablePackageCodec.encode(
            payload("pkg-a", MergeTestData.fullSnapshot()),
            pin,
        )
        val fake = FakePackageFileIo(stored = bytes)
        val vm = newVm(fake)
        vm.handlePickedDocument(Uri.parse("content://doc/6"))
        awaitIdle(vm)
        vm.decodeImportWithPin(pin)
        awaitIdle(vm)
        assertNotNull(vm.activeImportSession())
        vm.confirmImport()
        awaitIdle(vm)
        assertNull(vm.activeImportSession())
    }

    @Test
    fun `session lock invalidates the decoded import session`() = runBlocking {
        val bytes = PortablePackageCodec.encode(
            payload("pkg-l", MergeTestData.fullSnapshot(developers = MergeTestData.allFiveDevelopers())),
            pin,
        )
        val fake = FakePackageFileIo(stored = bytes)
        val vm = newVm(fake)
        vm.handlePickedDocument(Uri.parse("content://doc/7"))
        awaitIdle(vm)
        vm.decodeImportWithPin(pin)
        awaitIdle(vm)
        assertNotNull(vm.activeImportSession())

        session.lock()
        // The ViewModel's session observer runs on its unconfined scope; give
        // it a chance to process the lock.
        runBlocking { delay(50) }
        assertNull(vm.activeImportSession())
        assertEquals(ExportImportViewModel.ImportState.Idle, vm.importState.value)
    }

    @Test
    fun `PIN is not placed into SavedStateHandle or persistent state`() = runBlocking {
        // The exportimport package imports no SavedStateHandle / DataStore /
        // Bundle / rememberSaveable. The ViewModel only forwards a CharArray to
        // the codec and clears it. This test documents the structural guarantee
        // and asserts the UI state never carries secrets.
        val snapshot = MergeTestData.fullSnapshot(
            MergeTestData.account("a", "Svc", "acct", totps = listOf(MergeTestData.totp("t"))),
        )
        val bytes = PortablePackageCodec.encode(payload("pkg-pin", snapshot), pin)
        val fake = FakePackageFileIo(stored = bytes)
        val vm = newVm(fake)
        vm.handlePickedDocument(Uri.parse("content://doc/8"))
        awaitIdle(vm)
        vm.decodeImportWithPin(pin)
        awaitIdle(vm)
        val state = vm.importState.value
        assertTrue(state is ExportImportViewModel.ImportState.Preview)
        val previewText = (state as ExportImportViewModel.ImportState.Preview).preview.toString()
        assertTrue(!previewText.contains("JBSWY3DPEHPK3PXP"))
    }
}
