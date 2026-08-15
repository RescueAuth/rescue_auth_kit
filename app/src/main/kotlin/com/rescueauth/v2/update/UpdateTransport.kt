package com.rescueauth.v2.update

/**
 * Abstract fetch transport for the two fixed update reads (`latest.json` and
 * `latest.json.sig`).
 *
 * The production implementation uses Android's platform [HttpURLConnection]
 * (small, two GETs, no heavyweight network architecture). Tests inject a fake
 * [UpdateTransport] so unit tests never touch the real CNB source (Issue #20
 * §24).
 *
 * ## Contract
 *
 * - explicit connect timeout
 * - explicit read timeout
 * - cancellation-friendly (a cancelled job must abort the read)
 * - bounded response size (see [UpdateSizeLimits])
 * - no redirect to insecure HTTP (only HTTPS)
 * - no cookies / auth tokens / telemetry / analytics
 */
interface UpdateTransport {

    /** Fetches the manifest raw bytes (bounded to [UpdateSizeLimits.MAX_MANIFEST_BYTES]). */
    suspend fun fetchManifest(): ByteArray

    /** Fetches the signature text (bounded to [UpdateSizeLimits.MAX_SIGNATURE_BYTES]). */
    suspend fun fetchSignature(): String

    /** Returns true when the in-flight requests should be aborted (scope cancelled). */
    val isActive: Boolean
}
