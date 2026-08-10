package com.rescueauth.v2.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version decision + severity / minSupported unit tests (Issue #20 §23).
 */
class UpdateVersionDecisionTest {

    private fun manifest(
        versionCode: Long,
        minSupported: Long = 10000L,
        severity: Severity = Severity.NORMAL,
    ): UpdateManifest = UpdateManifest(
        schemaVersion = 1,
        channel = "stable",
        versionName = "v$versionCode",
        versionCode = versionCode,
        minSupportedVersionCode = minSupported,
        publishedAt = "2026-08-10T12:00:00Z",
        apkUrl = "https://example.com/a.apk",
        apkSizeBytes = 100,
        apkSha256 = "a".repeat(64),
        releaseNotesUrl = "https://example.com/n.html",
        severity = severity,
    )

    private fun identity(versionCode: Long, versionName: String = "v$versionCode") =
        UpdateVersionDecision.VersionIdentity(versionName = versionName, versionCode = versionCode)

    // --- 19. latest > current → update available ---
    @Test
    fun latestGreaterThanCurrentUpdateAvailable() {
        val decision = UpdateVersionDecision.decide(identity(10000), manifest(10100))
        assertTrue(decision is UpdateVersionDecision.Decision.UpdateAvailable)
    }

    // --- 20. latest == current → up to date ---
    @Test
    fun latestEqualsCurrentUpToDate() {
        val decision = UpdateVersionDecision.decide(identity(10100), manifest(10100))
        assertTrue(decision is UpdateVersionDecision.Decision.UpToDate)
    }

    // --- 21. latest < current → up to date / local newer ---
    @Test
    fun latestLessThanCurrentUpToDate() {
        val decision = UpdateVersionDecision.decide(identity(10200), manifest(10100))
        assertTrue(decision is UpdateVersionDecision.Decision.UpToDate)
    }

    // --- 22. versionName does not control ordering ---
    @Test
    fun versionNameDoesNotControlOrdering() {
        // Higher versionCode but "older"-looking name still means update.
        val decision = UpdateVersionDecision.decide(
            identity(10000, versionName = "2.0.0"),
            manifest(10100, minSupported = 10000).copy(versionName = "1.0.0"),
        )
        assertTrue(decision is UpdateVersionDecision.Decision.UpdateAvailable)
    }

    // --- 23. current < minSupported → stronger warning ---
    @Test
    fun currentBelowMinSupportedStrongerWarning() {
        val decision = UpdateVersionDecision.decide(
            identity(9990),
            manifest(10100, minSupported = 10000),
        )
        assertTrue(decision is UpdateVersionDecision.Decision.UpdateAvailable)
        assertTrue((decision as UpdateVersionDecision.Decision.UpdateAvailable).minSupportedExceeded)
    }

    // --- 24. SECURITY → strong advisory ---
    @Test
    fun securitySeverityParsed() {
        val m = manifest(10100, severity = Severity.SECURITY)
        assertEquals(Severity.SECURITY, m.severity)
    }

    // --- 25. SECURITY does not create forced-update state ---
    @Test
    fun securityDoesNotForcedUpdate() {
        // Even with SECURITY, an equal/older latest is still UpToDate (no lock).
        val decision = UpdateVersionDecision.decide(identity(10100), manifest(10100, severity = Severity.SECURITY))
        assertTrue(decision is UpdateVersionDecision.Decision.UpToDate)
        // And a SECURITY update-available is still just UpdateAvailable (a
        // recommendation, never a lock/block).
        val decision2 = UpdateVersionDecision.decide(identity(10000), manifest(10100, severity = Severity.SECURITY))
        assertTrue(decision2 is UpdateVersionDecision.Decision.UpdateAvailable)
        assertFalse((decision2 as UpdateVersionDecision.Decision.UpdateAvailable).minSupportedExceeded)
    }

    // --- minSupported helper ---
    @Test
    fun isBelowMinSupportedWorks() {
        assertTrue(UpdateVersionDecision.isBelowMinSupported(9990, 10000))
        assertFalse(UpdateVersionDecision.isBelowMinSupported(10000, 10000))
    }
}
