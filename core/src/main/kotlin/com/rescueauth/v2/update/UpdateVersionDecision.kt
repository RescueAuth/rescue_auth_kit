package com.rescueauth.v2.update

/**
 * Version decision logic (UPDATE_PROTOCOL.md / Issue #20 §12).
 *
 * ## Rule
 *
 * Only `versionCode` (a monotonically increasing integer) is used to decide
 * whether an update is available. `versionName` is display metadata only and
 * never controls ordering — no lexical string comparison, no semver-as-
 * authority (avoids the "1.10 vs 1.9" class of bugs).
 *
 * ```
 * latest.versionCode >  current.versionCode → UPDATE_AVAILABLE
 * latest.versionCode <= current.versionCode → UP_TO_DATE
 * ```
 *
 * ## minSupportedVersionCode
 *
 * If `current.versionCode < manifest.minSupportedVersionCode`, the UI shows a
 * stronger unsupported / outdated warning and strongly recommends upgrading.
 * It NEVER locks the Vault, NEVER blocks unlock, and NEVER blocks viewing /
 * exporting the user's own data (Issue #20 §11) — this app has no server-side
 * required protocol, and an update-service outage must never make the local
 * security vault unusable.
 */
object UpdateVersionDecision {

    sealed interface Decision {
        /** latest > current — an update is available. */
        data class UpdateAvailable(
            val current: UpdateManifest,
            val latest: UpdateManifest,
        ) : Decision {
            val minSupportedExceeded: Boolean
                get() = current.versionCode < latest.minSupportedVersionCode
        }

        /** latest <= current — no update. */
        object UpToDate : Decision
    }

    /**
     * Decides the outcome for [current] against a verified [latest] manifest.
     *
     * @param current the app's current version metadata (BuildConfig-backed).
     */
    fun decide(current: VersionIdentity, latest: UpdateManifest): Decision {
        return if (latest.versionCode > current.versionCode) {
            Decision.UpdateAvailable(current = current.toManifestLike(), latest = latest)
        } else {
            Decision.UpToDate
        }
    }

    /** Whether the current install is below [minSupportedVersionCode]. */
    fun isBelowMinSupported(currentVersionCode: Long, minSupportedVersionCode: Long): Boolean =
        currentVersionCode < minSupportedVersionCode

    /**
     * Lightweight version identity for the current install. Kept independent
     * of [UpdateManifest] so the app layer can build it from BuildConfig
     * without inventing a fake "manifest".
     */
    data class VersionIdentity(
        val versionName: String,
        val versionCode: Long,
    ) {
        internal fun toManifestLike(): UpdateManifest = UpdateManifest(
            schemaVersion = 1,
            channel = "stable",
            versionName = versionName,
            versionCode = versionCode,
            minSupportedVersionCode = versionCode,
            publishedAt = "",
            apkUrl = "",
            apkSizeBytes = 0,
            apkSha256 = "",
            releaseNotesUrl = "",
            severity = Severity.NORMAL,
        )
    }
}
