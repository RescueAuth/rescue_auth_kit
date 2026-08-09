package com.rescueauth.v2.exportimport

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.export.SelectedItemSet
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.codec.PortablePackageCodec
import com.rescueauth.v2.repository.MergeTestData
import com.rescueauth.v2.repository.VaultRepository
import com.rescueauth.v2.security.FakeSensitiveActionPrompt
import com.rescueauth.v2.security.SensitiveAction
import com.rescueauth.v2.security.SensitiveActionGate
import com.rescueauth.v2.security.SensitiveActionRequest
import com.rescueauth.v2.security.SensitiveActionResult
import com.rescueauth.v2.security.SensitiveActionTarget
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
 * Phase 4 P5 — ViewModel-level security tests (Issue #20 §27).
 *
 * 38. scope A authorization cannot authorize scope B pending request
 * 39. selected export auth bound to original export request
 * 40. session lock invalidates pending export
 * 41. decoded import snapshot cleared on lock
 * 42. no secret in preview UI state / string logs
 * 43. no PIN persisted
 * 44. no selection path contains plaintext secret
 *
 * Plus export integration tests 20–22 (every scope requires fresh re-auth;
 * auth cancel creates no SAF document; second export requires new auth) and
 * the export stale-selection flow (test 23 at the ViewModel level).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectiveExportImportViewModelTest {

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
    private val pin: CharArray get() = "123456".toCharArray()

    private fun controllableGate(): Pair<SensitiveActionGate, FakeSensitiveActionPrompt> {
        val prompt = FakeSensitiveActionPrompt()
        val gate = SensitiveActionGate(
            prompt = prompt,
            session = session,
            titleProvider = { "Authenticate to continue" },
            subtitleProvider = { null },
        )
        return gate to prompt
    }

    private fun exportRequest(scopeName: String = ExportScopeSpec.FullVault.name, digest: String? = null) =
        SensitiveActionRequest(
            action = SensitiveAction.EXPORT_PACKAGE,
            target = SensitiveActionTarget.ExportRequest(scopeName = scopeName, selectionDigest = digest),
        )

    private fun exportSuccess(request: SensitiveActionRequest) = SensitiveActionResult.Success(request)

    private fun newVm(fake: PackageFileIo, gate: SensitiveActionGate): ExportImportViewModel =
        ExportImportViewModel(
            context = context,
            serviceProvider = { service() },
            fileIo = fake,
            sessionState = session.state,
            scope = CoroutineScope(Dispatchers.Unconfined),
            sensitiveActionGate = gate,
        )

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

    // ---- 20. every export scope requires fresh re-auth ----

    @Test
    fun `every export scope requires fresh re-auth`() = runBlocking {
        for (scope in listOf(
            ExportScopeSpec.FullVault,
            ExportScopeSpec.Authenticator,
            ExportScopeSpec.Developer,
        )) {
            val (gate, prompt) = controllableGate()
            val vm = newVm(FakePackageFileIo(), gate)

            vm.beginExport(scope)
            assertEquals("scope=$scope", ExportImportViewModel.ExportState.AwaitingReauth, vm.exportState.value)
            assertEquals(1, prompt.startedRequests.size)
            val request = prompt.startedRequests.single()
            assertEquals(SensitiveAction.EXPORT_PACKAGE, request.action)
            val target = request.target as SensitiveActionTarget.ExportRequest
            assertEquals(scope.name, target.scopeName)
            assertTrue(vm.activeImportSession() == null)
        }
    }

    // ---- 21. auth cancel creates no SAF document ----

    @Test
    fun `auth cancel creates no SAF document for any scope`() = runBlocking {
        for (scope in listOf(
            ExportScopeSpec.FullVault,
            ExportScopeSpec.Authenticator,
            ExportScopeSpec.Developer,
        )) {
            val (gate, prompt) = controllableGate()
            val fake = FakePackageFileIo()
            val vm = newVm(fake, gate)

            vm.beginExport(scope)
            val request = prompt.startedRequests.single()
            prompt.deliver(SensitiveActionResult.Cancelled)
            assertEquals(ExportImportViewModel.ExportState.Idle, vm.exportState.value)
            assertTrue(fake.written.isEmpty())
            assertTrue(fake.deletedUris.isEmpty())
        }
    }

    // ---- 22. second export requires new auth (one-shot) ----

    @Test
    fun `second export requires a fresh re-auth`() = runBlocking {
        val (gate, prompt) = controllableGate()
        val vm = newVm(FakePackageFileIo(), gate)

        vm.beginExport(ExportScopeSpec.Authenticator)
        val req1 = prompt.startedRequests.single()
        prompt.deliver(exportSuccess(req1))
        assertEquals(ExportImportViewModel.ExportState.AwaitingPin, vm.exportState.value)
        vm.cancelExport()

        // Second export: fresh prompt required.
        vm.beginExport(ExportScopeSpec.Authenticator)
        assertEquals(ExportImportViewModel.ExportState.AwaitingReauth, vm.exportState.value)
        assertEquals(2, prompt.startedRequests.size)
    }

    // ---- 38. scope A authorization cannot authorize scope B ----

    @Test
    fun `scope A auth cannot authorize scope B`() = runBlocking {
        val (gate, prompt) = controllableGate()
        val fake = FakePackageFileIo()
        val vm = newVm(fake, gate)

        // Start an Authenticator-scope export and authorize it.
        vm.beginExport(ExportScopeSpec.Authenticator)
        val reqAuth = prompt.startedRequests.single()
        prompt.deliver(exportSuccess(reqAuth))
        assertEquals(ExportImportViewModel.ExportState.AwaitingPin, vm.exportState.value)

        // The authorization is consumed for the Authenticator request; a
        // Developer request with the SAME gate cannot reuse it.
        var ran = false
        val devReq = exportRequest(ExportScopeSpec.Developer.name)
        assertTrue(!gate.executePending(devReq) { ran = true })
        assertTrue(!ran)
        // And the original was consumed exactly once.
        assertTrue(!gate.executePending(reqAuth) { ran = true })
        assertTrue(!ran)
    }

    // ---- 39. selected export auth bound to original selection ----

    @Test
    fun `selected export auth bound to original selection digest`() = runBlocking {
        val (gate, prompt) = controllableGate()
        val vm = newVm(FakePackageFileIo(), gate)

        // Selected-items export: pick a TOTP, then re-auth.
        val selection = SelectedItemSet(selectedTotpStableIds = setOf("totp-a"))
        vm.beginExport(ExportScopeSpec.SelectedItems)
        // Load selectable list then commit the selection.
        vm.confirmExportSelection(selection)
        val request = prompt.startedRequests.single()
        val target = request.target as SensitiveActionTarget.ExportRequest
        assertEquals(ExportScopeSpec.SelectedItems.name, target.scopeName)
        assertEquals(selection.digest(), target.selectionDigest)

        prompt.deliver(exportSuccess(request))
        assertEquals(ExportImportViewModel.ExportState.AwaitingPin, vm.exportState.value)

        // A DIFFERENT selection digest cannot consume the authorization.
        val other = SelectedItemSet(selectedTotpStableIds = setOf("totp-b"))
        var ran = false
        val otherReq = exportRequest(ExportScopeSpec.SelectedItems.name, other.digest())
        assertTrue(!gate.executePending(otherReq) { ran = true })
        assertTrue(!ran)
    }

    // ---- 39b. auth for selection A cannot execute selection B ----

    @Test
    fun `auth for selection A cannot execute selection B`() = runBlocking {
        // Test at the gate level (the ViewModel consumes the one-shot auth as
        // soon as the prompt succeeds, so this scenario is tested directly on
        // the gate to prove the digest binding).
        val (gate, prompt) = controllableGate()
        val selectionA = SelectedItemSet(selectedTotpStableIds = setOf("totp-a"))
        val selectionB = SelectedItemSet(selectedTotpStableIds = setOf("totp-b"))
        val reqA = exportRequest(ExportScopeSpec.SelectedItems.name, selectionA.digest())
        val reqB = exportRequest(ExportScopeSpec.SelectedItems.name, selectionB.digest())

        assertTrue(gate.authorize(reqA) {})
        prompt.deliver(SensitiveActionResult.Success(reqA))
        assertTrue(gate.isAuthorizedFor(reqA))

        // Same kind, different stableId → different digest → MUST NOT run.
        var ranB = false
        assertTrue(!gate.executePending(reqB) { ranB = true })
        assertTrue(!ranB)

        // The canonical encoding guarantees the digests differ (collision
        // safety at the identity layer).
        assertTrue(selectionA.digest() != selectionB.digest())

        // Selection A itself runs exactly once (one-shot consumed).
        var ranA = false
        assertTrue(gate.executePending(reqA) { ranA = true })
        assertTrue(ranA)
        // And it was consumed exactly once — a second attempt is rejected.
        var ranAgain = false
        assertTrue(!gate.executePending(reqA) { ranAgain = true })
        assertTrue(!ranAgain)
    }

    // ---- 39c. same digest under different scope cannot be cross-executed ----

    @Test
    fun `different scope with identical selection cannot share authorization`() = runBlocking {
        // AUTHENTICATOR_ONLY and DEVELOPER_ONLY both carry an empty selection
        // digest (null). The scope name MUST still separate them even though
        // the digest would otherwise match.
        val authReq = exportRequest(ExportScopeSpec.Authenticator.name, null)
        val devReq = exportRequest(ExportScopeSpec.Developer.name, null)
        val auth = authReq.target as SensitiveActionTarget.ExportRequest
        val dev = devReq.target as SensitiveActionTarget.ExportRequest
        // Same digest VALUE (both null / empty selection) but different scope.
        assertEquals(auth.selectionDigest, dev.selectionDigest)
        assertTrue(auth.scopeName != dev.scopeName)

        // Authorize the Developer-scope request, then try to execute the
        // Authenticator-scope request with the same gate: MUST be rejected
        // even though the digest values are identical — the scope name is
        // part of the target identity.
        val (gate, prompt) = controllableGate()
        assertTrue(gate.authorize(devReq) {})
        prompt.deliver(SensitiveActionResult.Success(devReq))
        assertTrue(gate.isAuthorizedFor(devReq))

        var ran = false
        assertTrue(!gate.executePending(authReq) { ran = true })
        assertTrue(!ran)

        // And the Developer-scope request itself runs exactly once.
        var ranDev = false
        assertTrue(gate.executePending(devReq) { ranDev = true })
        assertTrue(ranDev)
    }

    // ---- 40. session lock invalidates pending export ----

    @Test
    fun `session lock invalidates pending export`() = runBlocking {
        val (gate, prompt) = controllableGate()
        val fake = FakePackageFileIo()
        val vm = newVm(fake, gate)

        vm.beginExport(ExportScopeSpec.Developer)
        assertEquals(ExportImportViewModel.ExportState.AwaitingReauth, vm.exportState.value)

        session.lock()
        gate.invalidate()
        runBlocking { delay(50) }
        assertEquals(ExportImportViewModel.ExportState.Idle, vm.exportState.value)
        assertTrue(fake.written.isEmpty())
    }

    // ---- 41. decoded import snapshot cleared on lock ----

    @Test
    fun `decoded import snapshot cleared on lock`() = runBlocking {
        val snapshot = MergeTestData.fullSnapshot(
            MergeTestData.account("a", "Svc", "acct", totps = listOf(MergeTestData.totp("t"))),
            developers = MergeTestData.allFiveDevelopers(),
        )
        val bytes = PortablePackageCodec.encode(
            com.rescueauth.v2.export.VaultPackagePayload(
                logicalSchemaVersion = 1,
                packageId = "pkg-lock",
                createdAt = "2024-01-01T00:00:00Z",
                snapshot = snapshot,
            ),
            pin,
        )
        val fake = FakePackageFileIo(stored = bytes)
        val (gate, _) = controllableGate()
        val vm = newVm(fake, gate)

        vm.handlePickedDocument(Uri.parse("content://doc/lock"))
        awaitIdle(vm)
        vm.decodeImportWithPin(pin)
        awaitIdle(vm)
        assertTrue(vm.activeImportSession() != null)

        session.lock()
        runBlocking { delay(50) }
        assertNull(vm.activeImportSession())
        assertEquals(ExportImportViewModel.ImportState.Idle, vm.importState.value)
    }

    // ---- 42. no secret in preview UI state / string logs ----

    @Test
    fun `no secret appears in import preview UI state`() = runBlocking {
        val snapshot = MergeTestData.fullSnapshot(
            MergeTestData.account(
                "a", "Svc", "acct",
                totps = listOf(MergeTestData.totp("t", secret = "JBSWY3DPEHPK3PXP")),
                recoverySets = listOf(
                    MergeTestData.recoverySet("set-1", codes = listOf(MergeTestData.recoveryCode("c-1", "SECRET-CODE"))),
                ),
            ),
            developers = MergeTestData.allFiveDevelopers(),
        )
        val bytes = PortablePackageCodec.encode(
            com.rescueauth.v2.export.VaultPackagePayload(
                logicalSchemaVersion = 1,
                packageId = "pkg-secret",
                createdAt = "2024-01-01T00:00:00Z",
                snapshot = snapshot,
            ),
            pin,
        )
        val fake = FakePackageFileIo(stored = bytes)
        val (gate, _) = controllableGate()
        val vm = newVm(fake, gate)

        vm.handlePickedDocument(Uri.parse("content://doc/s"))
        awaitIdle(vm)
        vm.decodeImportWithPin(pin)
        awaitIdle(vm)

        // Choosing-scope state string must not contain secrets.
        val choosingText = vm.importState.value.toString()
        assertTrue(!choosingText.contains("JBSWY3DPEHPK3PXP"))
        assertTrue(!choosingText.contains("SECRET-CODE"))
        assertTrue(!choosingText.contains("sk_test_123"))
        assertTrue(!choosingText.contains("PRIV-KEY"))

        // The selectable enumeration must not contain secrets either.
        val session = vm.activeImportSession()
        assertTrue(session != null)
        val selectable = com.rescueauth.v2.export.VaultSnapshotSelector.selectableItems(session!!.payload.snapshot)
        assertTrue(!selectable.toString().contains("JBSWY3DPEHPK3PXP"))
        assertTrue(!selectable.toString().contains("SECRET-CODE"))
        assertTrue(!selectable.toString().contains("sk_test_123"))
    }

    // ---- 43. no PIN persisted ----

    @Test
    fun `no PIN in ViewModel persistent state`() = runBlocking {
        val fake = FakePackageFileIo()
        val (gate, prompt) = controllableGate()
        val vm = newVm(fake, gate)

        vm.beginExport(ExportScopeSpec.FullVault)
        val req = prompt.startedRequests.single()
        prompt.deliver(exportSuccess(req))
        val reason = vm.submitExportPin(pin, pin.copyOf())
        assertNull(reason)
        assertEquals(ExportImportViewModel.ExportState.AwaitingDestination, vm.exportState.value)

        // The export state string never carries the PIN.
        assertTrue(!vm.exportState.value.toString().contains("123456"))

        vm.cancelExport()
        assertTrue(!vm.exportState.value.toString().contains("123456"))
    }

    // ---- 44. no selection path contains plaintext secret ----

    @Test
    fun `selection paths contain no plaintext secret`() = runBlocking {
        // The selection model + digest are stableId-based and never contain
        // secret values. A selection of a TOTP stableId has a digest that does
        // not include the secret.
        val selection = SelectedItemSet(selectedTotpStableIds = setOf("totp-1"))
        assertTrue(!selection.toString().contains("JBSWY3DPEHPK3PXP"))
        assertTrue(!selection.digest().contains("JBSWY3DPEHPK3PXP"))
    }

    // ---- stale export selection fails safely (ViewModel-level) ----

    @Test
    fun `stale export selection fails safely after re-auth`() = runBlocking {
        // Seed a vault, export scope selected for a TOTP, then delete the TOTP
        // before the final snapshot is built → StaleSelectionException path.
        val r = repo()
        val created = MergeTestData.account("a", "Svc", "acct", totps = listOf(MergeTestData.totp("t1")))
        r.applySnapshot(MergeTestData.fullSnapshot(created), "seed")

        val (gate, prompt) = controllableGate()
        val fake = FakePackageFileIo()
        val vm = newVm(fake, gate)

        val selection = SelectedItemSet(selectedTotpStableIds = setOf("t1"))
        vm.beginExport(ExportScopeSpec.SelectedItems)
        vm.confirmExportSelection(selection)
        val req = prompt.startedRequests.single()
        prompt.deliver(exportSuccess(req))
        assertEquals(ExportImportViewModel.ExportState.AwaitingPin, vm.exportState.value)
        val reason = vm.submitExportPin(pin, pin.copyOf())
        assertNull(reason)
        assertEquals(ExportImportViewModel.ExportState.AwaitingDestination, vm.exportState.value)

        // Delete the TOTP between re-auth + SAF destination and the encode.
        r.deleteAccount("a")

        vm.onExportDestinationPicked(Uri.parse("content://dest/stale"))
        awaitIdle(vm)
        val state = vm.exportState.value
        assertTrue("state=$state", state is ExportImportViewModel.ExportState.Error)
        assertTrue(fake.written.isEmpty())
        // Best-effort cleanup of the not-yet-written document.
        assertTrue(fake.deletedUris.isNotEmpty())
    }
}
