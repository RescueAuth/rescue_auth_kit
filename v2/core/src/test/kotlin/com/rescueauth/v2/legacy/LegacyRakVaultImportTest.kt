package com.rescueauth.v2.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compatibility suite for the legacy `.rakvault` import (Phase 1 acceptance).
 *
 * The 9 fixtures are the frozen assets under
 * `v2/legacy-fixtures` (manifest SHA-256 in docs/LEGACY_IMPORT.md §6).
 * The copies in `src/test/resources` must stay byte-identical to those.
 */
class LegacyRakVaultImportTest {

    private fun loadFixture(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("legacy-fixtures/$name.rakvault")
            ?.use { it.readBytes() }
            ?: error("fixture not found: $name")

    // ------------------------------------------------------------------
    // Positive: each schema must decrypt and parse to the expected bundle
    // ------------------------------------------------------------------

    @Test
    fun `schema1 normal fixture imports`() {
        val bundle = LegacyRakVaultImporter().import(loadFixture("schema1_normal"), "test-password-1")
        assertEquals(1, bundle.schemaVersion)
        assertEquals(5, bundle.totpEntries.size)
        assertEquals(1, bundle.recoveryCodeSets.size)

        val first = bundle.totpEntries.first()
        assertEquals("JBSWY3DPEHPK3PXP", first.secretBase32)
        assertEquals("SHA1", first.algorithm)
        assertEquals(6, first.digits)
        assertEquals(30, first.period)
        assertEquals(listOf("AAAA-BBBB-CCCC", "DDDD-EEEE-FFFF", "1111-2222-3333"),
            bundle.recoveryCodeSets.first().codes)
        assertEquals(0, bundle.developerCount)
    }

    @Test
    fun `schema2 normal fixture imports with developer entries`() {
        val bundle = LegacyRakVaultImporter().import(loadFixture("schema2_normal"), "test-password-2")
        assertEquals(2, bundle.schemaVersion)
        assertEquals(5, bundle.totpEntries.size)
        assertEquals(1, bundle.recoveryCodeSets.size)
        assertEquals(2, bundle.developerCount)
        assertTrue(bundle.developerSettings)
        assertEquals("apiCredential", bundle.developerEntries[0].type)
        assertEquals("sshKey", bundle.developerEntries[1].type)
    }

    @Test
    fun `schema3 normal fixture imports`() {
        val bundle = LegacyRakVaultImporter().import(loadFixture("schema3_normal"), "test-password-3")
        assertEquals(3, bundle.schemaVersion)
        assertEquals(2, bundle.totpEntries.size)
        assertEquals(1, bundle.recoveryCodeSets.size)
        assertEquals(1, bundle.developerCount)

        // TOTP credentials come from accounts alice (SHA1/6/30) and bob (SHA256/6/30)
        val byIssuer = bundle.totpEntries.associateBy { it.issuer }
        assertEquals("alice@example.com", byIssuer["GitHub"]?.accountName)
        assertEquals("JBSWY3DPEHPK3PXP", byIssuer["GitHub"]?.secretBase32)
        assertEquals("SHA256", byIssuer["Google"]?.algorithm)
    }

    @Test
    fun `rfc4226 vector fixture computes known TOTP at fixed test time`() {
        val bundle = LegacyRakVaultImporter().import(loadFixture("rfc4226_sha1_secret"), "rfc-test")
        val entry = bundle.totpEntries.single()
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", entry.secretBase32)

        // RFC 4226 test vectors use counter values directly. For a 30s period,
        // counter == unixTime/30, so unixTime = 30 * counter gives the same result.
        val expected = mapOf(
            0L to "755224",
            1L to "287082",
            2L to "359152",
            3L to "969429",
            4L to "338314",
            5L to "254676",
        )
        for ((counter, otp) in expected) {
            assertEquals("counter=$counter",
                otp,
                TotpVerifier.totpAt(entry.secretBase32, "SHA1", 6, 30, counter * 30))
        }
    }

    // ------------------------------------------------------------------
    // Negative: every corrupted / wrong-password fixture must fail safely
    // ------------------------------------------------------------------

    @Test
    fun `wrong password fails`() {
        val e = assertThrows(LegacyRakVaultImporter.ImportException::class.java) {
            LegacyRakVaultImporter().import(loadFixture("wrong_password"), "wrong-password")
        }
        assertTrue(e.message?.contains("AEAD", ignoreCase = true) == true ||
            e.message?.contains("authentication", ignoreCase = true) == true)
    }

    @Test
    fun `tampered ciphertext fails`() {
        assertThrows(LegacyRakVaultImporter.ImportException::class.java) {
            LegacyRakVaultImporter().import(loadFixture("tampered_ciphertext"), "test-password-1")
        }
    }

    @Test
    fun `truncated ciphertext fails`() {
        assertThrows(LegacyRakVaultImporter.ImportException::class.java) {
            LegacyRakVaultImporter().import(loadFixture("truncated_ciphertext"), "test-password-1")
        }
    }

    @Test
    fun `wrong mac fails`() {
        assertThrows(LegacyRakVaultImporter.ImportException::class.java) {
            LegacyRakVaultImporter().import(loadFixture("wrong_mac"), "test-password-1")
        }
    }

    @Test
    fun `extreme kdf params rejected before argon2`() {
        // The header claims 8 GiB memory; the importer MUST reject it during
        // header validation, before Argon2 is ever invoked (running it aborts
        // the process with an allocation failure).
        val e = assertThrows(LegacyRakVaultImporter.ImportException::class.java) {
            LegacyRakVaultImporter().import(loadFixture("extreme_kdf_params"), "test-password-1")
        }
        assertTrue(e.message?.contains("memoryKiB", ignoreCase = true) == true)
    }

    @Test
    fun `wrong password on schema1 fixture fails`() {
        assertThrows(LegacyRakVaultImporter.ImportException::class.java) {
            LegacyRakVaultImporter().import(loadFixture("schema1_normal"), "wrong-password")
        }
    }

    // ------------------------------------------------------------------
    // Data integrity
    // ------------------------------------------------------------------

    @Test
    fun `negative fixtures never produce a bundle`() {
        for (name in listOf(
            "wrong_password", "tampered_ciphertext",
            "truncated_ciphertext", "wrong_mac", "extreme_kdf_params",
        )) {
            assertThrows("fixture $name should fail", LegacyRakVaultImporter.ImportException::class.java) {
                LegacyRakVaultImporter().import(loadFixture(name), "test-password-1")
            }
        }
    }

    @Test
    fun `legacy developer entries are never silently dropped`() {
        val bundle = LegacyRakVaultImporter().import(loadFixture("schema2_normal"), "test-password-2")
        assertEquals(2, bundle.developerCount) // surfaced for preview / policy
        assertFalse(bundle.developerEntries.isEmpty())
    }

    // ------------------------------------------------------------------
    // RELEASE BLOCKER #46 — the import must classify failures into distinct
    // categories, never collapse them all into a single generic "vault
    // locked" style error. These lock the real-fixture error taxonomy.
    // ------------------------------------------------------------------

    private fun errorKind(name: String, password: String): LegacyRakVaultImporter.ErrorKind? {
        val e = assertThrows(LegacyRakVaultImporter.ImportException::class.java) {
            LegacyRakVaultImporter().import(loadFixture(name), password)
        }
        return e.kind
    }

    @Test
    fun `correct password imports successfully on the real frozen fixture`() {
        // Acceptance A: known-good real v1 `.rakvault` + correct password -> PASS.
        val bundle = LegacyRakVaultImporter()
            .import(loadFixture("schema3_normal"), "test-password-3")
        assertEquals(3, bundle.schemaVersion)
        assertEquals(2, bundle.totpEntries.size)
        assertEquals(1, bundle.recoveryCodeSets.size)
    }

    @Test
    fun `wrong password is classified as AUTHENTICATION_FAILED not generic`() {
        // Acceptance B: wrong password must be a distinct auth failure.
        assertEquals(
            LegacyRakVaultImporter.ErrorKind.AUTHENTICATION_FAILED,
            errorKind("schema3_normal", "wrong-password"),
        )
    }

    @Test
    fun `tampered ciphertext is classified as INVALID_ENVELOPE not auth`() {
        // Byte-level tampering corrupts the base64 envelope, so the file is
        // rejected as a malformed envelope (distinct from wrong password).
        assertEquals(
            LegacyRakVaultImporter.ErrorKind.INVALID_ENVELOPE,
            errorKind("tampered_ciphertext", "test-password-1"),
        )
    }

    @Test
    fun `truncated file is classified as a malformed envelope not auth`() {
        // A truncated file cannot even be parsed as a valid envelope.
        assertEquals(
            LegacyRakVaultImporter.ErrorKind.INVALID_ENVELOPE,
            errorKind("truncated_ciphertext", "test-password-1"),
        )
    }

    @Test
    fun `unsupported kdf params are classified as UNSUPPORTED_FORMAT not auth`() {
        // Extreme (out-of-safe-range) KDF params are rejected as unsupported
        // protection settings, NOT as a wrong password / vault locked.
        assertEquals(
            LegacyRakVaultImporter.ErrorKind.UNSUPPORTED_FORMAT,
            errorKind("extreme_kdf_params", "test-password-1"),
        )
    }
}
