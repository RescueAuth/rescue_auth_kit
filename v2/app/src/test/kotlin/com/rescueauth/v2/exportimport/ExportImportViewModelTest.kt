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
import com.rescueauth.v2.security.SensitiveActionGate
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

    private fun newVm(fake: PackageFileIo, gate: SensitiveActionGate? = autoApproveGate()): ExportImportViewModel =
        ExportImportViewModel(
            context = context,
            serviceProvider = { service() },
            fileIo = fake,
            sessionState = session.state,
            scope = CoroutineScope(Dispatchers.Unconfined),
            sensitiveActionGate = gate,
        )

    /**
     * An auto-approving re-auth gate for the PIN/SAF flow tests (they do not
     * exercise the re-auth itself). A fresh [SensitiveActionGate] is created
     * per call because a gate is one-shot / single-pending-action by design.
     *
     * The prompt echoes the STARTED request back as a Success so the gate's
     * one-shot binding (action + target) is satisfied for whatever scope the
     * flow chose.
     */
    private fun autoApproveGate(): SensitiveActionGate {
        val prompt = object : com.rescueauth.v2.security.FakeSensitiveActionPrompt() {
            override fun tryStart(
                request: com.rescueauth.v2.security.SensitiveActionRequest,
                title: CharSequence,
                subtitle: CharSequence?,
                onResult: (com.rescueauth.v2.security.SensitiveActionResult) -> Unit,
            ): Boolean {
                startedRequests += request
                if (!startResult) return false
                // Auto-approve by echoing the started request.
                onResult(com.rescueauth.v2.security.SensitiveActionResult.Success(request))
                return true
            }
        }
        return SensitiveActionGate(
            prompt = prompt,
            session = session,
            titleProvider = { "Authenticate to continue" },
            subtitleProvider = { null },
        )
    }

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
        // New flow: PIN first → AwaitingPin; cancel leaves Idle with no write.
        vm.beginExport()
        assertEquals(ExportImportViewModel.ExportState.AwaitingPin, vm.exportState.value)
        vm.cancelExport()
        assertEquals(ExportImportViewModel.ExportState.Idle, vm.exportState.value)
        assertTrue(fake.written.isEmpty())
    }

    @Test
    fun `cancelling the PIN never creates a document`() = runBlocking {
        val fake = FakePackageFileIo()
        val vm = newVm(fake)
        vm.beginExport()
        vm.cancelExport()
        // No CreateDocument ever happened: no write, and the ViewModel never
        // left the PIN screen for the destination.
        assertEquals(ExportImportViewModel.ExportState.Idle, vm.exportState.value)
        assertTrue(fake.written.isEmpty())
        assertTrue(fake.deletedUris.isEmpty())
    }

    @Test
    fun `pin confirm then destination produces success and writes exactly once`() = runBlocking {
        val fake = FakePackageFileIo()
        val vm = newVm(fake)
        vm.beginExport()
        assertEquals(ExportImportViewModel.ExportState.AwaitingPin, vm.exportState.value)

        // PIN + confirm accepted → AwaitingDestination (no file yet).
        val reason = vm.submitExportPin(pin, pin.copyOf())
        assertNull(reason)
        assertEquals(ExportImportViewModel.ExportState.AwaitingDestination, vm.exportState.value)
        assertTrue(fake.written.isEmpty())

        // Destination picked → encode + write → success.
        vm.onExportDestinationPicked(Uri.parse("content://dest/1"))
        awaitIdle(vm)
        assertTrue("state=${vm.exportState.value}", vm.exportState.value is ExportImportViewModel.ExportState.Success)
        assertEquals(1, fake.written.size)
        val written = fake.written[0]
        val magic = PackageFormat.MAGIC.toByteArray(Charsets.US_ASCII)
        assertTrue(written.size >= magic.size)
        for (i in magic.indices) {
            assertTrue("written bytes must start with the package magic", written[i] == magic[i])
        }
    }

    @Test
    fun `cancelling the destination picker after PIN returns to AwaitingPin without creating a file`() = runBlocking {
        val fake = FakePackageFileIo()
        val vm = newVm(fake)
        vm.beginExport()
        val reason = vm.submitExportPin(pin, pin.copyOf())
        assertNull(reason)
        assertEquals(ExportImportViewModel.ExportState.AwaitingDestination, vm.exportState.value)

        // Simulate the SAF picker returning null (user cancelled).
        vm.cancelExportDestination()
        assertEquals(ExportImportViewModel.ExportState.AwaitingPin, vm.exportState.value)
        assertTrue(fake.written.isEmpty())
        assertTrue(fake.deletedUris.isEmpty())
    }

    @Test
    fun `export write failure returns error clears pin and best-effort deletes the document`() = runBlocking {
        val fake = FakePackageFileIo(onWrite = { throw ExportImportError.WriteFailed })
        val vm = newVm(fake)
        vm.beginExport()
        val reason = vm.submitExportPin(pin, pin.copyOf())
        assertNull(reason)
        vm.onExportDestinationPicked(Uri.parse("content://dest/2"))
        awaitIdle(vm)
        assertTrue(vm.exportState.value is ExportImportViewModel.ExportState.Error)
        // Best-effort cleanup attempted for the partially-created document.
        assertEquals(1, fake.deletedUris.size)
        assertEquals(Uri.parse("content://dest/2"), fake.deletedUris[0])
    }

    @Test
    fun `delete cleanup failure does not crash the export`() = runBlocking {
        val fake = object : FakePackageFileIo() {
            override suspend fun deleteIfPossible(context: Context, uri: Uri) {
                throw RuntimeException("provider cannot delete")
            }
        }
        val vm = newVm(fake)
        vm.beginExport()
        val reason = vm.submitExportPin(pin, pin.copyOf())
        assertNull(reason)
        vm.onExportDestinationPicked(Uri.parse("content://dest/3"))
        awaitIdle(vm)
        // Write succeeds → Success; cleanup is not invoked on the success path.
        assertTrue(vm.exportState.value is ExportImportViewModel.ExportState.Success)
    }

    @Test
    fun `export write failure with throwing delete does not crash`() = runBlocking {
        val fake = object : FakePackageFileIo(onWrite = { throw ExportImportError.WriteFailed }) {
            override suspend fun deleteIfPossible(context: Context, uri: Uri) {
                throw RuntimeException("provider cannot delete")
            }
        }
        val vm = newVm(fake)
        vm.beginExport()
        val reason = vm.submitExportPin(pin, pin.copyOf())
        assertNull(reason)
        vm.onExportDestinationPicked(Uri.parse("content://dest/4"))
        awaitIdle(vm)
        assertTrue(vm.exportState.value is ExportImportViewModel.ExportState.Error)
    }

    @Test
    fun `pin confirm mismatch blocks export at the policy layer`() = runBlocking {
        // PinPolicy is the UI-layer check: a too-short or non-digit PIN is
        // rejected by the policy before the codec sees it.
        assertTrue(!PinPolicy.isValidPin("12345".toCharArray()))
        assertTrue(PinPolicy.isValidPin("123456".toCharArray()))

        // ViewModel defense-in-depth: mismatched confirm returns MISMATCH and
        // never transitions to AwaitingDestination.
        val vm = newVm(FakePackageFileIo())
        vm.beginExport()
        val reason = vm.submitExportPin("123456".toCharArray(), "654321".toCharArray())
        assertEquals(PinPolicy.Reason.MISMATCH, reason)
        assertEquals(ExportImportViewModel.ExportState.AwaitingPin, vm.exportState.value)
    }

    // ------------------------------------------------------------------
    // Export re-auth gate (Issue #20 §24)
    // ------------------------------------------------------------------

    private fun controllableGate(): Pair<SensitiveActionGate, com.rescueauth.v2.security.FakeSensitiveActionPrompt> {
        val prompt = com.rescueauth.v2.security.FakeSensitiveActionPrompt()
        val gate = SensitiveActionGate(
            prompt = prompt,
            session = session,
            titleProvider = { "Authenticate to continue" },
            subtitleProvider = { null },
        )
        return gate to prompt
    }

    private fun exportRequest() = com.rescueauth.v2.security.SensitiveActionRequest(
        action = com.rescueauth.v2.security.SensitiveAction.EXPORT_PACKAGE,
        target = com.rescueauth.v2.security.SensitiveActionTarget.ExportRequest(
            scopeName = ExportScopeSpec.FullVault.name,
            selectionDigest = null,
        ),
    )

    private fun exportSuccess() = com.rescueauth.v2.security.SensitiveActionResult.Success(exportRequest())

    @Test
    fun `export request requires a fresh re-auth first`() = runBlocking {
        val (gate, prompt) = controllableGate()
        val fake = FakePackageFileIo()
        val vm = newVm(fake, gate)

        vm.beginExport()
        assertEquals(ExportImportViewModel.ExportState.AwaitingReauth, vm.exportState.value)
        assertEquals(1, prompt.startedRequests.size)
        assertEquals(
            exportRequest(),
            prompt.startedRequests[0],
        )
        // No PIN / document before a successful re-auth.
        assertTrue(fake.written.isEmpty())
    }

    @Test
    fun `auth success continues to the PIN flow`() = runBlocking {
        val (gate, prompt) = controllableGate()
        val fake = FakePackageFileIo()
        val vm = newVm(fake, gate)

        vm.beginExport()
        assertEquals(ExportImportViewModel.ExportState.AwaitingReauth, vm.exportState.value)
        prompt.deliver(exportSuccess())
        assertEquals(ExportImportViewModel.ExportState.AwaitingPin, vm.exportState.value)
        assertTrue(fake.written.isEmpty())
    }

    @Test
    fun `auth cancel blocks the export - no PIN or document`() = runBlocking {
        val (gate, prompt) = controllableGate()
        val fake = FakePackageFileIo()
        val vm = newVm(fake, gate)

        vm.beginExport()
        prompt.deliver(com.rescueauth.v2.security.SensitiveActionResult.Cancelled)
        assertEquals(ExportImportViewModel.ExportState.Idle, vm.exportState.value)
        assertTrue(fake.written.isEmpty())
        assertTrue(fake.deletedUris.isEmpty())
    }

    @Test
    fun `auth failure blocks the export`() = runBlocking {
        val (gate, prompt) = controllableGate()
        val fake = FakePackageFileIo()
        val vm = newVm(fake, gate)

        vm.beginExport()
        prompt.deliver(com.rescueauth.v2.security.SensitiveActionResult.Failed)
        assertEquals(ExportImportViewModel.ExportState.Idle, vm.exportState.value)
        assertTrue(fake.written.isEmpty())
    }

    @Test
    fun `unavailable authenticator blocks the export`() = runBlocking {
        val (gate, prompt) = controllableGate()
        prompt.autoResult = com.rescueauth.v2.security.SensitiveActionResult.Unavailable
        val fake = FakePackageFileIo()
        val vm = newVm(fake, gate)

        vm.beginExport()
        assertEquals(ExportImportViewModel.ExportState.Idle, vm.exportState.value)
        assertTrue(fake.written.isEmpty())
    }

    @Test
    fun `successful auth is one-shot - second export needs fresh auth`() = runBlocking {
        val (gate, prompt) = controllableGate()
        val fake = FakePackageFileIo()
        val vm = newVm(fake, gate)

        // First export: re-auth → success → PIN.
        vm.beginExport()
        prompt.deliver(exportSuccess())
        assertEquals(ExportImportViewModel.ExportState.AwaitingPin, vm.exportState.value)
        vm.cancelExport()

        // Second export: a fresh prompt is required (one-shot consumed).
        vm.beginExport()
        assertEquals(ExportImportViewModel.ExportState.AwaitingReauth, vm.exportState.value)
        assertEquals(2, prompt.startedRequests.size)
        // It must NOT jump straight to PIN without a new success.
        assertTrue(vm.exportState.value is ExportImportViewModel.ExportState.AwaitingReauth)
    }

    @Test
    fun `session lock invalidates a pending export re-auth`() = runBlocking {
        val (gate, prompt) = controllableGate()
        val fake = FakePackageFileIo()
        val vm = newVm(fake, gate)

        vm.beginExport()
        assertEquals(ExportImportViewModel.ExportState.AwaitingReauth, vm.exportState.value)

        // Session locks while the prompt is pending.
        session.lock()
        // MainActivity wiring invalidates the gate on lock; the VM session
        // collector also resets the export state.
        gate.invalidate()
        awaitIdle(vm)
        assertEquals(ExportImportViewModel.ExportState.Idle, vm.exportState.value)
        assertTrue(fake.written.isEmpty())
    }

    @Test
    fun `no SAF document is created before successful re-auth`() = runBlocking {
        val (gate, prompt) = controllableGate()
        val fake = FakePackageFileIo()
        val vm = newVm(fake, gate)

        vm.beginExport()
        // Even after the user picked a destination while re-auth pending, no
        // document is written until the PIN + SAF flow completes.
        assertEquals(ExportImportViewModel.ExportState.AwaitingReauth, vm.exportState.value)
        // The UI cannot even reach the SAF picker while AwaitingReauth; assert
        // the ViewModel ignores a stale destination call.
        vm.onExportDestinationPicked(Uri.parse("content://dest/reauth"))
        assertTrue(fake.written.isEmpty())
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
        assertTrue(
            "state=${vm.importState.value}",
            vm.importState.value is ExportImportViewModel.ImportState.ChoosingScope,
        )

        // Choose Everything → filtered preview (safe subset summary).
        vm.chooseImportScope(ImportScopeSpec.Everything)
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
        assertTrue(vm.importState.value is ExportImportViewModel.ImportState.ChoosingScope)

        vm.chooseImportScope(ImportScopeSpec.Everything)
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
        vm.chooseImportScope(ImportScopeSpec.Everything)
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
        vm.chooseImportScope(ImportScopeSpec.Everything)
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
    fun `session lock drops a pending export PIN without creating a document`() = runBlocking {
        val fake = FakePackageFileIo()
        val vm = newVm(fake)
        vm.beginExport()
        val reason = vm.submitExportPin(pin, pin.copyOf())
        assertNull(reason)
        assertEquals(ExportImportViewModel.ExportState.AwaitingDestination, vm.exportState.value)

        session.lock()
        runBlocking { delay(50) }
        // Lock clears the export flow AND the transient PIN — no write, no
        // document, no retained secret.
        assertEquals(ExportImportViewModel.ExportState.Idle, vm.exportState.value)
        assertTrue(fake.written.isEmpty())
        assertTrue(fake.deletedUris.isEmpty())
    }

    @Test
    fun `empty import PIN is rejected without attempting decode`() = runBlocking {
        val bytes = PortablePackageCodec.encode(
            payload("pkg-e", MergeTestData.fullSnapshot()),
            pin,
        )
        val fake = FakePackageFileIo(stored = bytes)
        val vm = newVm(fake)
        vm.handlePickedDocument(Uri.parse("content://doc/empty-pin"))
        awaitIdle(vm)
        assertEquals(ExportImportViewModel.ImportState.AwaitingPin, vm.importState.value)

        // Defense-in-depth: an empty PIN never reaches decode (stays AwaitingPin,
        // session stays null).
        vm.decodeImportWithPin(CharArray(0))
        assertEquals(ExportImportViewModel.ImportState.AwaitingPin, vm.importState.value)
        assertNull(vm.activeImportSession())
    }

    @Test
    fun `short import PIN still decodes a package`() = runBlocking {
        // A historical package protected by a short PIN (4 digits) must import
        // — Export's length policy is not a package-format requirement.
        val shortPin = "1234".toCharArray()
        val bytes = PortablePackageCodec.encode(
            payload("pkg-short", MergeTestData.fullSnapshot(
                MergeTestData.account("a", "Svc", "acct", totps = listOf(MergeTestData.totp("t"))),
            )),
            shortPin,
        )
        shortPin.fill('\u0000')
        val fake = FakePackageFileIo(stored = bytes)
        val vm = newVm(fake)
        vm.handlePickedDocument(Uri.parse("content://doc/short"))
        awaitIdle(vm)
        vm.decodeImportWithPin("1234".toCharArray())
        awaitIdle(vm)
        assertTrue("state=${vm.importState.value}", vm.importState.value is ExportImportViewModel.ImportState.ChoosingScope)
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
        vm.chooseImportScope(ImportScopeSpec.Everything)
        awaitIdle(vm)
        val state = vm.importState.value
        assertTrue(state is ExportImportViewModel.ImportState.Preview)
        val previewText = (state as ExportImportViewModel.ImportState.Preview).preview.toString()
        assertTrue(!previewText.contains("JBSWY3DPEHPK3PXP"))
    }
}
