package com.rescueauth.v2.totp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production TOTP core tests (RFC 4226 / RFC 6238 known vectors + boundary
 * behaviour). Pure JVM, no Android dependencies.
 */
class TotpCoreTest {

    /** RFC 4226 Appendix D secret ("12345678901234567890"). */
    private val RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    private val RFC_SECRET_SHA256 =
        "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA===="
    private val RFC_SECRET_SHA512 =
        "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNA="

    // RFC 6238 Appendix B / RFC 4226 vectors.
    // count 59 -> T0=0, step 30, SHA1 => "94287082" (8-digit) / "287082" (6-digit).
    // count 1111111109 -> SHA1 8-digit "07081804" / 6-digit "081804".
    // count 1111111111 -> SHA1 8-digit "14050471" / 6-digit "050471".

    @Test
    fun `rfc vector count 59 sha1 8 digits`() {
        val code = TotpCore.generate(RFC_SECRET, "SHA1", 8, 30, 59)
        assertEquals("94287082", code)
    }

    @Test
    fun `rfc vector count 1111111109 sha1 8 digits`() {
        val code = TotpCore.generate(RFC_SECRET, "SHA1", 8, 30, 1_111_111_109L)
        assertEquals("07081804", code)
    }

    @Test
    fun `rfc vector count 1111111111 sha1 8 digits`() {
        val code = TotpCore.generate(RFC_SECRET, "SHA1", 8, 30, 1_111_111_111L)
        assertEquals("14050471", code)
    }

    @Test
    fun `rfc vector count 59 sha1 6 digits`() {
        val code = TotpCore.generate(RFC_SECRET, "SHA1", 6, 30, 59)
        assertEquals("287082", code)
    }

    @Test
    fun `rfc vector sha256 known value`() {
        // RFC 6238 Appendix B, SHA256 secret ("12345678901234567890123456789012").
        // count 59 -> 8-digit "46119246".
        val sha256Secret = RFC_SECRET_SHA256
        val code = TotpCore.generate(sha256Secret, "SHA256", 8, 30, 59)
        assertEquals("46119246", code)
    }

    @Test
    fun `rfc vector sha512 known value`() {
        // RFC 6238 Appendix B, SHA512 secret
        // ("1234567890123456789012345678901234567890123456789012345678901234").
        // count 59 -> 8-digit "90693936".
        val sha512Secret = RFC_SECRET_SHA512
        val code = TotpCore.generate(sha512Secret, "SHA512", 8, 30, 59)
        assertEquals("90693936", code)
    }

    @Test
    fun `digits 6 7 8 all work`() {
        for (digits in listOf(6, 7, 8)) {
            val code = TotpCore.generate(RFC_SECRET, "SHA1", digits, 30, 59)
            assertEquals(digits, code.length)
        }
    }

    @Test
    fun `invalid digits rejected`() {
        assertThrows(TotpCore.TotpException::class.java) {
            TotpCore.generate(RFC_SECRET, "SHA1", 5, 30, 59)
        }
        assertThrows(TotpCore.TotpException::class.java) {
            TotpCore.generate(RFC_SECRET, "SHA1", 9, 30, 59)
        }
    }

    @Test
    fun `invalid algorithm rejected`() {
        assertThrows(TotpCore.TotpException::class.java) {
            TotpCore.generate(RFC_SECRET, "MD5", 6, 30, 59)
        }
    }

    @Test
    fun `invalid period rejected`() {
        assertThrows(TotpCore.TotpException::class.java) {
            TotpCore.generate(RFC_SECRET, "SHA1", 6, 0, 59)
        }
    }

    @Test
    fun `invalid base32 secret rejected`() {
        assertThrows(TotpCore.TotpException::class.java) {
            TotpCore.generate("NOT!!BASE32", "SHA1", 6, 30, 59)
        }
    }

    @Test
    fun `empty secret rejected`() {
        assertThrows(TotpCore.TotpException::class.java) {
            TotpCore.generate("   ", "SHA1", 6, 30, 59)
        }
    }

    @Test
    fun `secret case and separators are normalised`() {
        val lower = "gezdgnbvgy3tqojqgezdgnbvgy3tqojq"
        val spaced = "GEZD GN BVGY3 TQOJQ GEZD GN BVGY3 TQOJQ"
        val expected = TotpCore.generate(RFC_SECRET, "SHA1", 6, 30, 59)
        assertEquals(expected, TotpCore.generate(lower, "SHA1", 6, 30, 59))
        assertEquals(expected, TotpCore.generate(spaced, "SHA1", 6, 30, 59))
    }

    @Test
    fun `algorithm case insensitive`() {
        assertEquals(
            TotpCore.generate(RFC_SECRET, "SHA1", 6, 30, 59),
            TotpCore.generate(RFC_SECRET, "sha1", 6, 30, 59),
        )
    }

    // ------------------------------------------------------------------
    // Period boundary / countdown
    // ------------------------------------------------------------------

    @Test
    fun `code is stable within a period and rotates at boundary`() {
        // period 30: t=0 and t=29 share a counter; t=30 rotates.
        val at0 = TotpCore.generate(RFC_SECRET, "SHA1", 6, 30, 0)
        val at29 = TotpCore.generate(RFC_SECRET, "SHA1", 6, 30, 29)
        val at30 = TotpCore.generate(RFC_SECRET, "SHA1", 6, 30, 30)
        assertEquals(at0, at29)
        assertTrue("code must rotate at period boundary", at0 != at30)
    }

    @Test
    fun `remaining seconds at boundaries`() {
        assertEquals(30, TotpCore.remainingSeconds(0, 30))
        assertEquals(1, TotpCore.remainingSeconds(29, 30))
        assertEquals(30, TotpCore.remainingSeconds(30, 30))
        assertEquals(1, TotpCore.remainingSeconds(59, 60))
    }

    @Test
    fun `progress fraction within bounds`() {
        val f = TotpCore.progressFraction(15, 30)
        assertEquals(0.5f, f, 1e-6f)
        val full = TotpCore.progressFraction(0, 30)
        assertEquals(1.0f, full, 1e-6f)
    }

    @Test
    fun `validation flags`() {
        assertTrue(TotpCore.isValidBase32("GEZDGNBVGY3TQOJQ"))
        assertFalse(TotpCore.isValidBase32(""))
        assertFalse(TotpCore.isValidBase32("NOT!!BASE32"))
    }
}
