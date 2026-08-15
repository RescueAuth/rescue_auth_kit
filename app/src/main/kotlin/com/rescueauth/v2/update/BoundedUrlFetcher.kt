package com.rescueauth.v2.update

import java.net.HttpURLConnection
import java.net.URL

/**
 * Bounded, cancellation-friendly HTTPS fetch of a fixed update URL using the
 * platform [HttpURLConnection].
 *
 * - explicit connect + read timeouts
 * - bounded response size (never unbounded `readBytes`/`readText` from the
 *   network)
 * - rejects any redirect that downgrades to HTTP (only HTTPS is followed)
 * - no cookies, no auth tokens, no telemetry
 * - the caller can abort via [isActive] between reads
 */
object BoundedUrlFetcher {

    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 10_000

    /**
     * Fetches up to [maxBytes] from [urlText] (must be HTTPS).
     *
     * @throws UpdateNetworkException on network failure, timeout, non-2xx,
     *   oversized response, or insecure redirect.
     */
    fun fetch(urlText: String, maxBytes: Int, isActive: () -> Boolean): ByteArray {
        val url = URL(urlText)
        if (url.protocol != "https") {
            throw UpdateNetworkException(UpdateNetworkException.Type.NETWORK, "insecure scheme")
        }
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept-Encoding", "identity")
            // User-Agent: only app name + public version, no device fingerprint.
            connection.setRequestProperty("User-Agent", "RescueAuth-v2")

            val code = connection.responseCode
            if (code !in 200..299) {
                throw UpdateNetworkException(UpdateNetworkException.Type.NETWORK, "HTTP $code")
            }
            // Enforce HTTPS on the final URL (after any redirect).
            val finalUrl = connection.url
            if (finalUrl.protocol != "https") {
                throw UpdateNetworkException(UpdateNetworkException.Type.NETWORK, "redirect to insecure")
            }

            val input = connection.inputStream
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                if (!isActive()) {
                    throw UpdateNetworkException(UpdateNetworkException.Type.CANCELLED, "cancelled")
                }
                val n = try {
                    input.read(buf)
                } catch (e: java.net.SocketTimeoutException) {
                    throw UpdateNetworkException(UpdateNetworkException.Type.TIMEOUT, "read timeout")
                }
                if (n < 0) break
                total += n
                if (total > maxBytes) {
                    throw UpdateNetworkException(UpdateNetworkException.Type.TOO_LARGE, "exceeded $maxBytes")
                }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } catch (e: UpdateNetworkException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw UpdateNetworkException(UpdateNetworkException.Type.TIMEOUT, e.message)
        } catch (e: java.net.UnknownHostException) {
            throw UpdateNetworkException(UpdateNetworkException.Type.NETWORK, e.message)
        } catch (e: java.io.IOException) {
            throw UpdateNetworkException(UpdateNetworkException.Type.NETWORK, e.message)
        } finally {
            connection.disconnect()
        }
    }
}

/** Structured network failure mapped to the update state machine taxonomy. */
class UpdateNetworkException(val type: Type, message: String?) :
    RuntimeException(message) {

    enum class Type {
        NETWORK,
        TIMEOUT,
        TOO_LARGE,
        CANCELLED,
    }
}
