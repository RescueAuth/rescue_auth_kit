package com.rescueauth.v2.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production [UpdateTransport] using the platform [BoundedUrlFetcher].
 *
 * Fetches only the two fixed HTTPS URLs. Runs on a background dispatcher.
 * The [isActive] flag lets the caller abort in-flight reads when the owning
 * scope is cancelled (Issue #20 §20 process/lifecycle).
 */
class HttpUpdateTransport(
    private val activeCheck: () -> Boolean = { true },
) : UpdateTransport {

    override suspend fun fetchManifest(): ByteArray = withContext(Dispatchers.IO) {
        BoundedUrlFetcher.fetch(
            UpdateManifestSource.MANIFEST_URL,
            UpdateSizeLimits.MAX_MANIFEST_BYTES,
            activeCheck,
        )
    }

    override suspend fun fetchSignature(): String = withContext(Dispatchers.IO) {
        val bytes = BoundedUrlFetcher.fetch(
            UpdateManifestSource.SIGNATURE_URL,
            UpdateSizeLimits.MAX_SIGNATURE_BYTES,
            activeCheck,
        )
        bytes.decodeToString()
    }

    override val isActive: Boolean get() = activeCheck()
}
