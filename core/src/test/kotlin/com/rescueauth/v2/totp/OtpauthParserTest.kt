package com.rescueauth.v2.totp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the pure-Kotlin otpauth:// TOTP parser. */
class OtpauthParserTest {

    @Test
    fun `normal uri parses secret issuer account and defaults`() {
        val uri = "otpauth://totp/GitHub:alice%40example.com?secret=JBSWY3DPEHPK3PXP"
        val parsed = OtpauthParser.parse(uri)
        assertEquals("GitHub", parsed.issuer)
        assertEquals("alice@example.com", parsed.accountName)
        assertEquals("JBSWY3DPEHPK3PXP", parsed.secretBase32)
        assertEquals("SHA1", parsed.algorithm)
        assertEquals(6, parsed.digits)
        assertEquals(30, parsed.periodSeconds)
    }

    @Test
    fun `issuer query wins over label prefix`() {
        val uri =
            "otpauth://totp/LabelIssuer:account?secret=JBSWY3DPEHPK3PXP&issuer=QueryIssuer"
        val parsed = OtpauthParser.parse(uri)
        assertEquals("QueryIssuer", parsed.issuer)
        assertEquals("account", parsed.accountName)
    }

    @Test
    fun `issuer query with no label prefix`() {
        val uri = "otpauth://totp/account?secret=JBSWY3DPEHPK3PXP&issuer=GitHub"
        val parsed = OtpauthParser.parse(uri)
        assertEquals("GitHub", parsed.issuer)
        assertEquals("account", parsed.accountName)
    }

    @Test
    fun `percent encoded label decodes`() {
        val uri = "otpauth://totp/My%20Service:user%2Btag%40example.com?secret=JBSWY3DPEHPK3PXP"
        val parsed = OtpauthParser.parse(uri)
        assertEquals("My Service", parsed.issuer)
        assertEquals("user+tag@example.com", parsed.accountName)
    }

    @Test
    fun `algorithm digits period parsed`() {
        val uri = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP" +
            "&algorithm=SHA256&digits=8&period=60"
        val parsed = OtpauthParser.parse(uri)
        assertEquals("SHA256", parsed.algorithm)
        assertEquals(8, parsed.digits)
        assertEquals(60, parsed.periodSeconds)
    }

    @Test
    fun `digits 9 parsed`() {
        val uri = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&digits=9"
        val parsed = OtpauthParser.parse(uri)
        assertEquals(9, parsed.digits)
    }

    @Test
    fun `digits 10 parsed`() {
        val uri = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&digits=10"
        val parsed = OtpauthParser.parse(uri)
        assertEquals(10, parsed.digits)
    }

    @Test
    fun `period 1 parsed`() {
        val uri = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&period=1"
        val parsed = OtpauthParser.parse(uri)
        assertEquals(1, parsed.periodSeconds)
    }

    @Test
    fun `period 120 parsed`() {
        val uri = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&period=120"
        val parsed = OtpauthParser.parse(uri)
        assertEquals(120, parsed.periodSeconds)
    }

    @Test
    fun `lowercase algorithm is normalised`() {
        val uri = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&algorithm=sha512"
        assertEquals("SHA512", OtpauthParser.parse(uri).algorithm)
    }

    @Test
    fun `secret whitespace and lowercase normalised`() {
        val uri = "otpauth://totp/GitHub:alice?secret=jbs wy3d-pehp-k3pxp"
        assertEquals("JBSWY3DPEHPK3PXP", OtpauthParser.parse(uri).secretBase32)
    }

    @Test
    fun `label without issuer`() {
        val uri = "otpauth://totp/alice%40example.com?secret=JBSWY3DPEHPK3PXP"
        val parsed = OtpauthParser.parse(uri)
        assertNull(parsed.issuer)
        assertEquals("alice@example.com", parsed.accountName)
    }

    @Test
    fun `empty account allowed when issuer present`() {
        val uri = "otpauth://totp/GitHub:?secret=JBSWY3DPEHPK3PXP"
        val parsed = OtpauthParser.parse(uri)
        assertEquals("GitHub", parsed.issuer)
        assertNull(parsed.accountName)
    }

    // ------------------------------------------------------------------
    // Rejections
    // ------------------------------------------------------------------

    @Test
    fun `missing secret rejected`() {
        val uri = "otpauth://totp/GitHub:alice"
        val e = assertThrows(OtpauthParser.OtpauthParseException::class.java) {
            OtpauthParser.parse(uri)
        }
        assertTrue(e.message!!.contains("secret"))
    }

    @Test
    fun `malformed base32 secret rejected`() {
        val uri = "otpauth://totp/GitHub:alice?secret=NOT!!VALID"
        assertThrows(OtpauthParser.OtpauthParseException::class.java) {
            OtpauthParser.parse(uri)
        }
    }

    @Test
    fun `hotp rejected`() {
        val uri = "otpauth://hotp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&counter=1"
        val e = assertThrows(OtpauthParser.OtpauthParseException::class.java) {
            OtpauthParser.parse(uri)
        }
        assertTrue(e.message!!.contains("HOTP"))
    }

    @Test
    fun `malformed uri without scheme rejected`() {
        assertThrows(OtpauthParser.OtpauthParseException::class.java) {
            OtpauthParser.parse("totp/GitHub:alice?secret=x")
        }
    }

    @Test
    fun `wrong scheme rejected`() {
        assertThrows(OtpauthParser.OtpauthParseException::class.java) {
            OtpauthParser.parse("https://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP")
        }
    }

    @Test
    fun `unknown otpauth type rejected`() {
        assertThrows(OtpauthParser.OtpauthParseException::class.java) {
            OtpauthParser.parse("otpauth://foo/GitHub:alice?secret=JBSWY3DPEHPK3PXP")
        }
    }

    @Test
    fun `unsupported algorithm rejected`() {
        val uri = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&algorithm=MD5"
        assertThrows(OtpauthParser.OtpauthParseException::class.java) {
            OtpauthParser.parse(uri)
        }
    }

    @Test
    fun `invalid digits rejected`() {
        val uri = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&digits=5"
        assertThrows(OtpauthParser.OtpauthParseException::class.java) {
            OtpauthParser.parse(uri)
        }
    }

    @Test
    fun `out of range high digits rejected`() {
        val uri = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&digits=11"
        assertThrows(OtpauthParser.OtpauthParseException::class.java) {
            OtpauthParser.parse(uri)
        }
    }

    @Test
    fun `non numeric digits rejected`() {
        val uri = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&digits=abc"
        assertThrows(OtpauthParser.OtpauthParseException::class.java) {
            OtpauthParser.parse(uri)
        }
    }

    @Test
    fun `invalid period rejected`() {
        val uri = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&period=0"
        assertThrows(OtpauthParser.OtpauthParseException::class.java) {
            OtpauthParser.parse(uri)
        }
    }

    @Test
    fun `out of range high period rejected`() {
        val uri = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&period=121"
        assertThrows(OtpauthParser.OtpauthParseException::class.java) {
            OtpauthParser.parse(uri)
        }
    }

    @Test
    fun `blank secret rejected`() {
        val uri = "otpauth://totp/GitHub:alice?secret=%20%20"
        assertThrows(OtpauthParser.OtpauthParseException::class.java) {
            OtpauthParser.parse(uri)
        }
    }
}
