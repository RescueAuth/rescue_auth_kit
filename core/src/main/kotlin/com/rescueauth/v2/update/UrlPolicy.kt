package com.rescueauth.v2.update

import java.net.URI

/**
 * URL safety policy for the update protocol (UPDATE_PROTOCOL.md / Issue #20
 * §14 Redirect / URL safety).
 *
 * - The fixed manifest source must be HTTPS.
 * - `releaseNotesUrl` / `apkUrl` are only usable AFTER signature verification,
 *   and only when `https` scheme (never `file:` / `content:` / `intent:` /
 *   `javascript:` / `data:` / `http:`).
 * - The same check is re-applied immediately before the external open so a
 *   verified-but-hostile manifest URL can never become a clickable arbitrary
 *   deep link.
 */
object UrlPolicy {

    private val ALLOWED_SCHEMES = setOf("https")

    /** True when [url] is a syntactically valid absolute HTTPS URL. */
    fun isSecureHttpUrl(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        return uri.scheme?.lowercase() in ALLOWED_SCHEMES && !uri.rawAuthority.isNullOrEmpty()
    }

    /**
     * The scheme gate applied right before an external open. Rejects every
     * non-HTTPS scheme explicitly (defense-in-depth after signature verify).
     */
    fun isAllowedExternalScheme(scheme: String?): Boolean =
        scheme?.lowercase() in ALLOWED_SCHEMES
}
