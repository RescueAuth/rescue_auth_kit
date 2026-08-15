package com.rescueauth.v2.update

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * External "Open Release Page" (Issue #20 §13, §14).
 *
 * - Opens the verified HTTPS `releaseNotesUrl` via Android external
 *   `ACTION_VIEW` (system browser). No WebView.
 * - The URL is only offered after the manifest signature was verified; the
 *   scheme is re-checked immediately before opening.
 * - No APK download into app storage, no PackageInstaller, no
 *   REQUEST_INSTALL_PACKAGES.
 */
object ExternalOpenHelper {

    /**
     * Opens [url] externally if it is a verified, allowed HTTPS URL.
     *
     * @return true when an external view intent was launched.
     */
    fun openReleasePage(context: Context, url: String?): Boolean {
        if (url == null) return false
        if (!UrlPolicy.isSecureHttpUrl(url)) return false
        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return false
        }
        // Re-check the scheme right before opening (defense-in-depth).
        if (!UrlPolicy.isAllowedExternalScheme(uri.scheme)) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val resolve = context.packageManager.resolveActivity(intent, 0)
            if (resolve != null) {
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
