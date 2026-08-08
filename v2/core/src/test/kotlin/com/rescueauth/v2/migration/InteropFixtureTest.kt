package com.rescueauth.v2.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Interoperability fixture tests (Phase 4 P2 compatibility CR).
 *
 * Proves the parser can read **real Google Authenticator v6.0 export URIs**
 * (synthetic test accounts only) and a **protoc-generated** fixture — neither
 * of which is produced by this project's [ProtoFixture] builder or
 * MinimalProtobuf encoder — and that every decoded value (issuer / name /
 * algorithm / digits / secret Base32 / batch metadata) matches the expected
 * values from the independent decode.
 */
class InteropFixtureTest {

    private fun parse(uri: String): MigrationParseResult = MigrationPayloadParser.parseUri(uri)

    private fun assertEntry(expected: GoogleFixtureEntry, actual: MigrationTotpCandidate) {
        assertEquals(expected.name, actual.name)
        assertEquals(expected.issuer, actual.issuer)
        assertEquals(expected.algorithm, actual.algorithm)
        assertEquals(expected.digits, actual.digits)
        assertEquals(expected.secretBase32, actual.secretBase32)
    }

    // ------------------------------------------------------------------
    // A. Real Google Authenticator v6.0 exports (synthetic test accounts)
    // ------------------------------------------------------------------

    @Test
    fun `ga v6 test1 single qr decodes with real enum wire values`() {
        val result = parse(InteropFixtures.GA_TEST1_URI)
        // 2 TOTP importable + 1 HOTP unsupported
        assertEquals(2, result.importableCount)
        assertEquals(1, result.unsupportedCount)

        val totpEntries = result.entries.filter { it.status == MigrationEntryStatus.IMPORTABLE }
        val expectedTotp = InteropFixtures.GA_TEST1_EXPECTED.filter { it.type == "TOTP" }
        assertEquals(expectedTotp.size, totpEntries.size)
        expectedTotp.zip(totpEntries).forEach { (exp, act) -> assertEntry(exp, act) }

        // HOTP correctly rejected as unsupported, never imported.
        val hotp = result.entries.first { it.status == MigrationEntryStatus.UNSUPPORTED }
        assertEquals("hotp-not-supported", hotp.reason)
        assertNull(hotp.secretBase32)

        // Batch metadata comes from inside the protobuf (single-QR batch).
        assertEquals(InteropFixtures.GA_TEST1_BATCH_SIZE, result.batchSize)
        assertEquals(InteropFixtures.GA_TEST1_BATCH_INDEX, result.batchIndex)
        assertEquals(InteropFixtures.GA_TEST1_BATCH_ID, result.batchId)
    }

    @Test
    fun `ga v6 sha512 8digit export decodes algorithm and digits`() {
        val result = parse(InteropFixtures.GA_SHA512_8DIGIT_URI)
        assertEquals(1, result.importableCount)
        val e = result.entries[0]
        assertEntry(InteropFixtures.GA_SHA512_8DIGIT_EXPECTED[0], e)
        assertEquals(1, result.batchSize)
        assertEquals(0, result.batchIndex)
        assertEquals(28672797, result.batchId)
    }

    @Test
    fun `ga v6 multi qr part 1 decodes batch metadata and entries`() {
        val result = parse(InteropFixtures.GA_TEST2_QR1_URI)
        assertEquals(2, result.batchSize)
        assertEquals(0, result.batchIndex)
        assertEquals(InteropFixtures.GA_TEST2_BATCH_ID, result.batchId)

        // 9 TOTP importable + 1 HOTP unsupported
        assertEquals(9, result.importableCount)
        assertEquals(1, result.unsupportedCount)

        val totp = result.entries.filter { it.status == MigrationEntryStatus.IMPORTABLE }
        val expected = InteropFixtures.GA_TEST2_QR1_EXPECTED.filter { it.type == "TOTP" }
        assertEquals(expected.size, totp.size)
        expected.zip(totp).forEach { (exp, act) -> assertEntry(exp, act) }
    }

    @Test
    fun `ga v6 multi qr part 2 decodes batch metadata and entries`() {
        val result = parse(InteropFixtures.GA_TEST2_QR2_URI)
        assertEquals(2, result.batchSize)
        assertEquals(1, result.batchIndex)
        assertEquals(InteropFixtures.GA_TEST2_BATCH_ID, result.batchId)
        assertEquals(2, result.importableCount)

        val expected = InteropFixtures.GA_TEST2_QR2_EXPECTED
        result.entries.forEachIndexed { i, act -> assertEntry(expected[i], act) }
    }

    // ------------------------------------------------------------------
    // B. protoc-generated fixture (independent protobuf implementation)
    // ------------------------------------------------------------------

    @Test
    fun `protoc fixture standard padded base64 parses to expected values`() {
        val uri = "otpauth-migration://offline?data=${percentEncode(InteropFixtures.PROTOC_FIXTURE_STANDARD_PADDED)}"
        val result = parse(uri)

        // 2 TOTP importable + 1 HOTP unsupported
        assertEquals(2, result.importableCount)
        assertEquals(1, result.unsupportedCount)

        val totp = result.entries.filter { it.status == MigrationEntryStatus.IMPORTABLE }
        val expected = InteropFixtures.PROTOC_FIXTURE_EXPECTED.filter { it.type == "TOTP" }
        assertEquals(expected.size, totp.size)
        expected.zip(totp).forEach { (exp, act) -> assertEntry(exp, act) }

        // Batch metadata read from the protobuf (NOT from query params).
        assertEquals(InteropFixtures.PROTOC_FIXTURE_BATCH_SIZE, result.batchSize)
        assertEquals(InteropFixtures.PROTOC_FIXTURE_BATCH_INDEX, result.batchIndex)
        assertEquals(InteropFixtures.PROTOC_FIXTURE_BATCH_ID, result.batchId)
    }

    @Test
    fun `protoc fixture urlsafe no padding parses identically`() {
        val uri = "otpauth-migration://offline?data=${percentEncode(InteropFixtures.PROTOC_FIXTURE_URLSAFE_NOPAD)}"
        val result = parse(uri)
        assertEquals(2, result.importableCount)
        assertEquals(1, result.unsupportedCount)
        assertEquals(InteropFixtures.PROTOC_FIXTURE_BATCH_SIZE, result.batchSize)
        assertEquals(InteropFixtures.PROTOC_FIXTURE_BATCH_INDEX, result.batchIndex)
        assertEquals(InteropFixtures.PROTOC_FIXTURE_BATCH_ID, result.batchId)
        val totp = result.entries.filter { it.status == MigrationEntryStatus.IMPORTABLE }
        val expected = InteropFixtures.PROTOC_FIXTURE_EXPECTED.filter { it.type == "TOTP" }
        expected.zip(totp).forEach { (exp, act) -> assertEntry(exp, act) }
    }

    // ------------------------------------------------------------------
    // Batch metadata source (compatibility CR §3)
    // ------------------------------------------------------------------

    @Test
    fun `protoc fixture batch metadata comes from protobuf only no query params`() {
        // The URI has NO &batch_size= / &batch_index= / &batch_id= query
        // params; the parser must still recover batch metadata from the
        // decoded MigrationPayload.
        val uri = "otpauth-migration://offline?data=${percentEncode(InteropFixtures.PROTOC_FIXTURE_STANDARD_PADDED)}"
        assertTrue(!uri.contains("batch_size="))
        assertTrue(!uri.contains("batch_index="))
        assertTrue(!uri.contains("batch_id="))
        val result = parse(uri)
        assertEquals(3, result.batchSize)
        assertEquals(1, result.batchIndex)
        assertEquals(424242, result.batchId)
    }

    @Test
    fun `ga v6 multi qr batch metadata comes from protobuf only`() {
        // Real Google multi-QR URIs carry batch metadata ONLY in the decoded
        // payload — there are no &batch_* query parameters.
        val uri = InteropFixtures.GA_TEST2_QR1_URI
        assertTrue(!uri.contains("batch_size="))
        val result = parse(uri)
        assertEquals(2, result.batchSize)
        assertEquals(0, result.batchIndex)
        assertEquals(27091391, result.batchId)
    }

    private fun percentEncode(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when {
                c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~' -> sb.append(c)
                else -> sb.append('%').append(String.format("%02X", c.code and 0xff))
            }
        }
        return sb.toString()
    }
}
