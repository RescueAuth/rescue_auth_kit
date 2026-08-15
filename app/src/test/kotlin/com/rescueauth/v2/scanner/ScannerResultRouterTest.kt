package com.rescueauth.v2.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the raw-QR → parser routing (Phase 4 P2). */
class ScannerResultRouterTest {

    @Test
    fun `otpauth totp routes to existing parser`() {
        val result = ScannerResultRouter.route(
            "otpauth://totp/GitHub:alice%40example.com?secret=JBSWY3DPEHPK3PXP"
        )
        assertTrue(result is ScannerResultRouter.ScanResult.Totp)
        val totp = result as ScannerResultRouter.ScanResult.Totp
        assertEquals("GitHub", totp.parsed.issuer)
        assertEquals("alice@example.com", totp.parsed.accountName)
    }

    @Test
    fun `malformed otpauth routes to typed error`() {
        val result = ScannerResultRouter.route("otpauth://totp/GitHub:alice")
        assertTrue(result is ScannerResultRouter.ScanResult.MalformedOtpauth)
    }

    @Test
    fun `migration routes to migration parser`() {
        val uri = MigrationTestFixtures.migrationUri(listOf(MigrationTestFixtures.otpEntry()))
        val result = ScannerResultRouter.route(uri)
        assertTrue(result is ScannerResultRouter.ScanResult.Migration)
        val migration = result as ScannerResultRouter.ScanResult.Migration
        assertEquals(1, migration.parsed.importableCount)
    }

    @Test
    fun `malformed migration routes to typed error`() {
        val result = ScannerResultRouter.route("otpauth-migration://offline?data=%%%")
        assertTrue(result is ScannerResultRouter.ScanResult.MalformedMigration)
    }

    @Test
    fun `unrelated qr routes to not supported`() {
        val result = ScannerResultRouter.route("https://example.com")
        assertEquals(ScannerResultRouter.ScanResult.NotSupported, result)
    }

    @Test
    fun `empty string routes to not supported`() {
        assertEquals(ScannerResultRouter.ScanResult.NotSupported, ScannerResultRouter.route(""))
    }
}
