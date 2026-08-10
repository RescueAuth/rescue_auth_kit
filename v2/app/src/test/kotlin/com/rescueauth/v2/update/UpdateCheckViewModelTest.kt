package com.rescueauth.v2.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App / network layer tests for the update check (Issue #20 §24).
 *
 * Uses an injected fake transport — unit tests never access the real CNB
 * source. Also verifies fail-closed update data / fail-open app semantics, no
 * URL exposure on signature failure, retry, and scope-cancellation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckViewModelTest {

    private val keyPair = TestEd25519.generateKeyPair()
    private val trustConfig = UpdateTrustConfig(keyPair.publicKeyBase64)

    private fun TestScope.viewModel(
        transport: UpdateTransport,
        trust: UpdateTrustConfig = trustConfig,
        scope: CoroutineScope? = null,
        currentVersionCode: Long = 10000L,
        currentVersionName: String = "1.0.0",
    ): Pair<UpdateCheckViewModel, CoroutineScope> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val s = scope ?: CoroutineScope(dispatcher + Job())
        val vm = UpdateCheckViewModel(
            transport = transport,
            trustConfig = trust,
            versionIdentity = {
                UpdateVersionDecision.VersionIdentity(currentVersionName, currentVersionCode)
            },
            scope = s,
        )
        return vm to s
    }

    private fun FakeUpdateTransport.signedManifest(
        versionCode: Long = 10100L,
        severity: String = "NORMAL",
        minSupported: Long = 10000L,
    ) {
        val json = TestManifestJson.build(
            versionCode = versionCode,
            severity = severity,
            minSupportedVersionCode = minSupported,
        )
        manifestResponse = json.toByteArray()
        signatureResponse = TestEd25519.sign(keyPair.privateKey, json.toByteArray())
    }

    // --- 26. network error → NETWORK ---
    @Test
    fun networkErrorMapsToNetwork() = runTest {
        val transport = FakeUpdateTransport().apply { manifestError = UpdateNetworkException(UpdateNetworkException.Type.NETWORK, "down") }
        val (vm, _) = viewModel(transport)
        vm.check()
        testScheduler.advanceUntilIdle()
        val st = vm.state.value
        assertTrue(st is UpdateUiState.Error)
        assertEquals(UpdateUiState.ErrorType.NETWORK, (st as UpdateUiState.Error).type)
    }

    // --- 27. timeout → TIMEOUT ---
    @Test
    fun timeoutMapsToTimeout() = runTest {
        val transport = FakeUpdateTransport().apply { manifestError = UpdateNetworkException(UpdateNetworkException.Type.TIMEOUT, "slow") }
        val (vm, _) = viewModel(transport)
        vm.check()
        testScheduler.advanceUntilIdle()
        val st = vm.state.value
        assertTrue(st is UpdateUiState.Error)
        assertEquals(UpdateUiState.ErrorType.TIMEOUT, (st as UpdateUiState.Error).type)
    }

    // --- 28. oversized manifest rejected before unbounded allocation ---
    @Test
    fun oversizedManifestRejected() = runTest {
        val transport = FakeUpdateTransport().apply {
            manifestResponse = ByteArray(UpdateSizeLimits.MAX_MANIFEST_BYTES + 1)
            signatureResponse = TestEd25519.sign(keyPair.privateKey, manifestResponse)
        }
        val (vm, _) = viewModel(transport)
        vm.check()
        testScheduler.advanceUntilIdle()
        val st = vm.state.value
        assertTrue(st is UpdateUiState.Error)
        assertEquals(UpdateUiState.ErrorType.INVALID_MANIFEST, (st as UpdateUiState.Error).type)
    }

    // --- 29. oversized signature rejected ---
    @Test
    fun oversizedSignatureRejected() = runTest {
        val transport = FakeUpdateTransport().apply {
            manifestResponse = TestManifestJson.defaultBytes()
            signatureResponse = "A".repeat(UpdateSizeLimits.MAX_SIGNATURE_BYTES + 1)
        }
        val (vm, _) = viewModel(transport)
        vm.check()
        testScheduler.advanceUntilIdle()
        val st = vm.state.value
        assertTrue(st is UpdateUiState.Error)
        assertEquals(UpdateUiState.ErrorType.INVALID_SIGNATURE, (st as UpdateUiState.Error).type)
    }

    // --- 30. invalid signature → no manifest URL exposed ---
    @Test
    fun invalidSignatureNoUrlExposed() = runTest {
        val transport = FakeUpdateTransport().apply {
            val json = TestManifestJson.build()
            manifestResponse = json.toByteArray()
            signatureResponse = "bad-signature"
        }
        val (vm, _) = viewModel(transport)
        vm.check()
        testScheduler.advanceUntilIdle()
        val st = vm.state.value
        assertTrue(st is UpdateUiState.Error)
        assertEquals(UpdateUiState.ErrorType.INVALID_SIGNATURE, (st as UpdateUiState.Error).type)
        assertNull("no verified open URL on signature failure", vm.verifiedOpenUrl())
    }

    // --- 31. malformed verified manifest → INVALID_MANIFEST ---
    @Test
    fun malformedVerifiedManifestInvalid() = runTest {
        val transport = FakeUpdateTransport()
        val malformed = "{ not valid json ]"
        transport.manifestResponse = malformed.toByteArray()
        transport.signatureResponse = TestEd25519.sign(keyPair.privateKey, malformed.toByteArray())
        val (vm, _) = viewModel(transport)
        vm.check()
        testScheduler.advanceUntilIdle()
        val st = vm.state.value
        assertTrue(st is UpdateUiState.Error)
        assertEquals(UpdateUiState.ErrorType.INVALID_MANIFEST, (st as UpdateUiState.Error).type)
    }

    // --- 32. public key not configured → NOT_CONFIGURED ---
    @Test
    fun publicKeyNotConfigured() = runTest {
        val transport = FakeUpdateTransport()
        val (vm, _) = viewModel(transport, trust = UpdateTrustConfig.notConfigured())
        vm.check()
        testScheduler.advanceUntilIdle()
        val st = vm.state.value
        assertTrue(st is UpdateUiState.Error)
        assertEquals(UpdateUiState.ErrorType.NOT_CONFIGURED, (st as UpdateUiState.Error).type)
    }

    // --- 33. second manual Retry can succeed ---
    @Test
    fun retryCanSucceed() = runTest {
        val transport = FakeUpdateTransport()
        transport.manifestError = UpdateNetworkException(UpdateNetworkException.Type.NETWORK, "first fail")
        val (vm, _) = viewModel(transport)
        vm.check()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value is UpdateUiState.Error)

        // Now it works.
        transport.manifestError = null
        transport.signedManifest(versionCode = 10100)
        vm.check()
        testScheduler.advanceUntilIdle()
        val st = vm.state.value
        assertTrue("retry should succeed", st is UpdateUiState.UpdateAvailable)
    }

    // --- 34. leaving scope cancels in-flight request ---
    @Test
    fun leavingScopeCancelsInFlightRequest() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + Job())
        val transport = FakeUpdateTransport().apply { blockManifestUntilCancelled = true }
        val (vm, _) = viewModel(transport, scope = scope)
        vm.check()
        // Cancel the owning scope (leaving the screen) before the fetch finishes.
        scope.cancel()
        testScheduler.advanceUntilIdle()
        // After cancellation the check is aborted — never a stuck Checking that
        // could block the UI.
        assertTrue(vm.state.value is UpdateUiState.Idle)
    }

    // --- 35. no auth token/cookie/user data added to request ---
    @Test
    fun noAuthOrUserDataInRequest() = runTest {
        val transport = FakeUpdateTransport()
        transport.signedManifest()
        val (vm, _) = viewModel(transport)
        vm.check()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value is UpdateUiState.UpdateAvailable)
        // The fake transport exposes no secret-bearing request fields.
        assertTrue(transport.observedRequestHeaders.isEmpty())
    }

    // --- update available wiring ---
    @Test
    fun updateAvailableWithReleaseNotesUrl() = runTest {
        val transport = FakeUpdateTransport().apply { signedManifest(versionCode = 10100) }
        val (vm, _) = viewModel(transport)
        vm.check()
        testScheduler.advanceUntilIdle()
        val st = vm.state.value
        assertTrue(st is UpdateUiState.UpdateAvailable)
        val url = (st as UpdateUiState.UpdateAvailable).latest.releaseNotesUrl
        assertEquals(TestManifestJson.RELEASE_NOTES_URL, url)
        assertEquals(TestManifestJson.RELEASE_NOTES_URL, vm.verifiedOpenUrl())
    }
}
