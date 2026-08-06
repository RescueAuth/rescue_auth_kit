package com.rescueauth.v2.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 fix: corrected schema 1/2 mapping + invalid TOTP policy.
 *
 * Contract (docs/LEGACY_IMPORT.md §5):
 * 1. Each legacy totpEntry maps to its OWN AuthAccount, preserving its own
 *    issuer, accountName and TotpCredential verbatim. Same serviceName may
 *    only group in the UI — never merge or drop accountNames.
 * 2. Schema 3 stays one legacy Account → one AuthAccount.
 * 3. Unknown/invalid algorithm/digits/period are preserved raw and listed in
 *    the "not imported" report — never silently imported as SHA1/6/30.
 */
class LegacyToV2MapperTest {

    private fun loadFixture(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("legacy-fixtures/$name.rakvault")
            ?.use { it.readBytes() }
            ?: error("fixture not found: $name")

    private fun import(name: String, password: String = "test-password-1"): LegacyImportResult {
        val bundle = LegacyRakVaultImporter().import(loadFixture(name), password)
        return LegacyToV2Mapper.map(bundle)
    }

    // ------------------------------------------------------------------
    // 1. Same issuer + multiple account names → one AuthAccount per entry
    // ------------------------------------------------------------------

    @Test
    fun `same issuer multiple accounts map one-to-one`() {
        val result = import("schema1_same_issuer_multi_account")

        // 3 TOTP entries + 1 recovery set = 4 AuthAccounts
        assertEquals(4, result.accounts.size)
        assertEquals(0, result.report.notImportedCount)

        val totpAccounts = result.accounts.filter { it.totpCredentials.isNotEmpty() }
        assertEquals(3, totpAccounts.size)

        // No two accounts share the same legacy source
        assertEquals(
            3,
            totpAccounts.map { it.legacySourceId }.distinct().size,
        )

        // Each account keeps its own accountName AND its own single credential
        val byAccount = totpAccounts.associateBy { it.accountName }
        assertEquals(setOf("alice@example.com", "bob@example.com", "carol@example.com"),
            byAccount.keys)

        val alice = byAccount.getValue("alice@example.com")
        assertEquals("GitHub", alice.serviceName)
        assertEquals("JBSWY3DPEHPK3PXP", alice.totpCredentials.single().secretBase32)
        assertEquals("SHA1", alice.totpCredentials.single().algorithm)
        assertEquals(6, alice.totpCredentials.single().digits)
        assertEquals(30, alice.totpCredentials.single().periodSeconds)

        val bob = byAccount.getValue("bob@example.com")
        assertEquals("SHA256", bob.totpCredentials.single().algorithm)

        val carol = byAccount.getValue("carol@example.com")
        assertEquals("SHA512", carol.totpCredentials.single().algorithm)
        assertEquals(8, carol.totpCredentials.single().digits)

        // Service name is a grouping field: all three share "GitHub"
        assertEquals(3, totpAccounts.count { it.serviceName == "GitHub" })
    }

    @Test
    fun `same issuer accounts keep distinct sort order`() {
        val result = import("schema1_same_issuer_multi_account")
        val orders = result.accounts.map { it.sortOrder }
        assertEquals(orders.sorted(), orders)
        assertEquals(4, orders.toSet().size)
    }

    // ------------------------------------------------------------------
    // 2. Invalid TOTP params → preserved raw + not-imported report
    // ------------------------------------------------------------------

    @Test
    fun `unknown algorithm is reported not silently defaulted`() {
        val result = import("schema1_invalid_totp_params")

        // 6 entries: 1 valid + 5 invalid → only 1 account
        assertEquals(1, result.accounts.size)
        assertEquals("alice@example.com", result.accounts.single().accountName)
        assertEquals(5, result.report.notImportedCount)

        val badAlgo = result.report.notImported.first { it.serviceName == "Google" }
        assertEquals("unknown algorithm 'MD5'", badAlgo.reason)
        assertEquals("MD5", badAlgo.rawAlgorithm)
    }

    @Test
    fun `invalid digits preserved in report`() {
        val result = import("schema1_invalid_totp_params")
        val bad = result.report.notImported.first { it.accountName == "admin@example.com" }
        assertEquals(0, bad.rawDigits)
        assertTrue(bad.reason.contains("digits"))
    }

    @Test
    fun `invalid period preserved in report`() {
        val result = import("schema1_invalid_totp_params")
        val bad = result.report.notImported.first { it.accountName == "dave@example.com" }
        assertEquals(-30, bad.rawPeriod)
        assertTrue(bad.reason.contains("period"))
    }

    @Test
    fun `missing algorithm is reported`() {
        val result = import("schema1_invalid_totp_params")
        val bad = result.report.notImported.first { it.accountName == "carol@example.com" }
        assertEquals("missing algorithm", bad.reason)
        assertEquals("", bad.rawAlgorithm)
    }

    @Test
    fun `malformed base32 is reported not imported`() {
        val result = import("schema1_invalid_totp_params")
        val bad = result.report.notImported.first { it.accountName == "eve@example.com" }
        assertTrue(bad.reason.contains("base32"))
        // The secret is malformed — the entry must NOT be imported at all,
        // even though its algorithm/digits/period are otherwise valid.
        assertTrue(result.accounts.none { it.accountName == "eve@example.com" })
        assertEquals("SHA1", bad.rawAlgorithm)
        assertEquals(6, bad.rawDigits)
    }

    @Test
    fun `valid entry in same file still imports`() {
        val result = import("schema1_invalid_totp_params")
        val account = result.accounts.single()
        assertEquals("JBSWY3DPEHPK3PXP", account.totpCredentials.single().secretBase32)
        assertEquals("SHA1", account.totpCredentials.single().algorithm)
        assertEquals(6, account.totpCredentials.single().digits)
    }

    // ------------------------------------------------------------------
    // 3. Schema 3 stays account-centric
    // ------------------------------------------------------------------

    @Test
    fun `schema3 keeps one account per legacy account`() {
        val result = import("schema3_normal", "test-password-3")

        // 2 accounts, 2 TOTP credentials, 1 recovery set
        assertEquals(2, result.accounts.size)
        val totpAccounts = result.accounts.filter { it.totpCredentials.isNotEmpty() }
        assertEquals(2, totpAccounts.size)

        val byName = totpAccounts.associateBy { it.accountName }
        assertEquals("GitHub", byName.getValue("alice@example.com").serviceName)
        assertEquals("JBSWY3DPEHPK3PXP",
            byName.getValue("alice@example.com").totpCredentials.single().secretBase32)
        assertEquals("SHA256",
            byName.getValue("bob@example.com").totpCredentials.single().algorithm)
        assertEquals(0, result.report.notImportedCount)
    }

    // ------------------------------------------------------------------
    // Regression: schema 1/2 normal fixtures still map correctly
    // ------------------------------------------------------------------

    @Test
    fun `schema1 normal maps one account per entry`() {
        val result = import("schema1_normal")
        // 5 TOTP entries (distinct issuers) + 1 recovery set = 6 accounts
        assertEquals(6, result.accounts.size)
        assertEquals(5, result.accounts.count { it.totpCredentials.isNotEmpty() })
        assertEquals(1, result.accounts.count { it.recoveryCodeSets.isNotEmpty() })
        assertEquals(0, result.report.notImportedCount)
    }

    @Test
    fun `schema2 normal maps one account per entry`() {
        val result = import("schema2_normal", "test-password-2")
        assertEquals(6, result.accounts.size)
        assertEquals(5, result.accounts.count { it.totpCredentials.isNotEmpty() })
        assertEquals(1, result.accounts.count { it.recoveryCodeSets.isNotEmpty() })
        assertEquals(0, result.report.notImportedCount)
    }
}
