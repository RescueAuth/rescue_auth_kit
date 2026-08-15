package com.rescueauth.v2.update

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * TEST-ONLY fake [UpdateTransport] that never touches the real CNB source.
 *
 * It lets tests script manifest + signature responses (or throw network
 * errors), record whether the request was cancelled, and assert that no auth
 * token / cookie / user data is attached.
 */
class FakeUpdateTransport : UpdateTransport {

    @Volatile
    var manifestResponse: ByteArray = byteArrayOf()
    var signatureResponse: String = ""

    @Volatile
    var manifestError: UpdateNetworkException? = null
    var signatureError: UpdateNetworkException? = null

    /** When true, the next manifest fetch blocks forever until cancelled. */
    @Volatile
    var blockManifestUntilCancelled = false

    var manifestFetchCount = 0
    var signatureFetchCount = 0

    /** Set by the test to model scope cancellation. */
    @Volatile
    override var isActive: Boolean = true

    // Test-assertable request metadata: this fake never attaches any auth /
    // cookie / user data, mirroring the production transport contract.
    val observedRequestHeaders = mutableListOf<Map<String, String>>()

    override suspend fun fetchManifest(): ByteArray {
        manifestFetchCount++
        if (blockManifestUntilCancelled) {
            while (currentCoroutineContext().isActive) {
                kotlinx.coroutines.yield()
            }
            throw UpdateNetworkException(UpdateNetworkException.Type.CANCELLED, "cancelled")
        }
        manifestError?.let { throw it }
        return manifestResponse
    }

    override suspend fun fetchSignature(): String {
        signatureFetchCount++
        signatureError?.let { throw it }
        return signatureResponse
    }
}
