package com.rescueauth.v2.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Pure-core tests for the otpauth-migration payload parser (synthetic fixtures).
 *
 * These tests use the project's own [ProtoFixture] builder for ordinary unit
 * coverage. The **real protocol definition source** is the independent
 * interoperability contract in [InteropFixtures] / [InteropFixtureTest]
 * (real Google Authenticator v6.0 exports + protoc-generated fixture).
 */
class MigrationPayloadParserTest {

    /** Standard RFC 4648 §4 Base64 (padded) — the real GA wire form. */
    private fun base64std(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    private fun uri(data: ByteArray, query: String = ""): String {
        val base = "otpauth-migration://offline?data=${base64std(data)}"
        return if (query.isEmpty()) base else "$base&$query"
    }

    // RFC 4648 test vectors for raw-secret -> Base32 (no padding).
    private fun base32(bytes: ByteArray): String = MigrationPayloadParser.encodeBase32NoPadding(bytes)

    private fun assertMalformed(uri: String, reason: String? = null) {
        val ex = assertThrows(MigrationPayloadParser.MigrationParseException::class.java) {
            MigrationPayloadParser.parseUri(uri)
        }
        if (reason != null) assertEquals(reason, ex.reason)
    }

    @Test
    fun `01 single totp parses as importable`() {
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry()))
        val result = MigrationPayloadParser.parseUri(uri(data))
        assertEquals(1, result.entries.size)
        val e = result.entries[0]
        assertEquals(MigrationEntryStatus.IMPORTABLE, e.status)
        assertEquals("GitHub", e.issuer)
        assertEquals("alice@example.com", e.name)
        assertEquals("SHA1", e.algorithm)
        assertEquals(6, e.digits)
        assertEquals(30, e.periodSeconds)
    }

    @Test
    fun `02 multiple totp entries parsed`() {
        val data = ProtoFixture.payload(
            listOf(
                ProtoFixture.otpEntry(name = "a@example.com", issuer = "One"),
                ProtoFixture.otpEntry(name = "b@example.com", issuer = "Two"),
                ProtoFixture.otpEntry(name = "c@example.com", issuer = "Three"),
            )
        )
        val result = MigrationPayloadParser.parseUri(uri(data))
        assertEquals(3, result.entries.size)
        assertTrue(result.entries.all { it.status == MigrationEntryStatus.IMPORTABLE })
        assertEquals(3, result.importableCount)
    }

    @Test
    fun `03 issuer and name carried through`() {
        val data = ProtoFixture.payload(
            listOf(ProtoFixture.otpEntry(name = "user%40corp.com", issuer = "Acme Corp"))
        )
        val e = MigrationPayloadParser.parseUri(uri(data)).entries[0]
        assertEquals("Acme Corp", e.issuer)
        assertEquals("user%40corp.com", e.name)
    }

    @Test
    fun `04 raw secret bytes convert to exact base32`() {
        // "Hello!" (RFC 4648 base32, no padding) -> "JBSWY3DPEE"
        val secret = "Hello!".toByteArray(Charsets.UTF_8)
        assertEquals("JBSWY3DPEE", base32(secret))
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry(secret = secret)))
        val e = MigrationPayloadParser.parseUri(uri(data)).entries[0]
        assertEquals("JBSWY3DPEE", e.secretBase32)
    }

    @Test
    fun `05 sha1 algorithm`() {
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry(algorithm = ProtoFixture.ALGO_SHA1)))
        assertEquals("SHA1", MigrationPayloadParser.parseUri(uri(data)).entries[0].algorithm)
    }

    @Test
    fun `06 sha256 algorithm`() {
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry(algorithm = ProtoFixture.ALGO_SHA256)))
        assertEquals("SHA256", MigrationPayloadParser.parseUri(uri(data)).entries[0].algorithm)
    }

    @Test
    fun `07 sha512 algorithm`() {
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry(algorithm = ProtoFixture.ALGO_SHA512)))
        assertEquals("SHA512", MigrationPayloadParser.parseUri(uri(data)).entries[0].algorithm)
    }

    @Test
    fun `08 enum digits six and eight mapped`() {
        // Google DigitCount: 0=UNSPECIFIED, 1=SIX(6), 2=EIGHT(8)
        val six = ProtoFixture.payload(listOf(ProtoFixture.otpEntry(digits = ProtoFixture.DIGITS_SIX)))
        assertEquals(6, MigrationPayloadParser.parseUri(uri(six)).entries[0].digits)

        val eight = ProtoFixture.payload(listOf(ProtoFixture.otpEntry(digits = ProtoFixture.DIGITS_EIGHT)))
        assertEquals(8, MigrationPayloadParser.parseUri(uri(eight)).entries[0].digits)

        val unspecified = ProtoFixture.payload(listOf(ProtoFixture.otpEntry(digits = ProtoFixture.DIGITS_UNSPECIFIED)))
        assertEquals(6, MigrationPayloadParser.parseUri(uri(unspecified)).entries[0].digits)
    }

    @Test
    fun `09 malformed base64 rejected`() {
        val bad = "otpauth-migration://offline?data=%%%NOT-BASE64%%%"
        val ex = assertThrows(MigrationPayloadParser.MigrationParseException::class.java) {
            MigrationPayloadParser.parseUri(bad)
        }
        assertTrue(ex.reason.isNotEmpty())
    }

    @Test
    fun `10 malformed protobuf rejected`() {
        // Valid standard base64 but garbage bytes: 0xFF 0xFF is not a valid message.
        val bad = "otpauth-migration://offline?data=${base64std(byteArrayOf(0xff.toByte(), 0xff.toByte()))}"
        assertThrows(MinimalProtobuf.MalformedPayloadException::class.java) {
            MigrationPayloadParser.parseUri(bad)
        }
    }

    @Test
    fun `11 missing secret marked invalid not crash`() {
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry(secret = null)))
        val result = MigrationPayloadParser.parseUri(uri(data))
        assertEquals(1, result.entries.size)
        assertEquals(MigrationEntryStatus.INVALID, result.entries[0].status)
        assertEquals(0, result.importableCount)
        assertEquals(1, result.invalidCount)
    }

    @Test
    fun `12 hotp marked unsupported`() {
        // Google OtpType: 1 = HOTP (protobuf enum, not raw-int 0)
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry(type = ProtoFixture.TYPE_HOTP)))
        val result = MigrationPayloadParser.parseUri(uri(data))
        val e = result.entries[0]
        assertEquals(MigrationEntryStatus.UNSUPPORTED, e.status)
        assertEquals("hotp-not-supported", e.reason)
    }

    @Test
    fun `13 md5 algorithm marked unsupported`() {
        // Google Algorithm: 4 = MD5 (protobuf enum, not SHA224)
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry(algorithm = ProtoFixture.ALGO_MD5)))
        val e = MigrationPayloadParser.parseUri(uri(data)).entries[0]
        assertEquals(MigrationEntryStatus.UNSUPPORTED, e.status)
        assertEquals("unsupported-algorithm", e.reason)
        // Display token carries the real GA enum name, never a guessed value.
        assertEquals("MD5", e.algorithm)
    }

    @Test
    fun `13b unspecified algorithm maps to sha1 default`() {
        // Google Algorithm.UNSPECIFIED = 0 → SHA1 (native v2 default).
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry(algorithm = ProtoFixture.ALGO_UNSPECIFIED)))
        val e = MigrationPayloadParser.parseUri(uri(data)).entries[0]
        assertEquals(MigrationEntryStatus.IMPORTABLE, e.status)
        assertEquals("SHA1", e.algorithm)
    }

    @Test
    fun `14 mixed valid plus unsupported entries`() {
        val data = ProtoFixture.payload(
            listOf(
                ProtoFixture.otpEntry(name = "good", issuer = "GitHub"),
                ProtoFixture.otpEntry(name = "hotp", issuer = "X", type = ProtoFixture.TYPE_HOTP),
                ProtoFixture.otpEntry(name = "md5", issuer = "Y", algorithm = ProtoFixture.ALGO_MD5),
                ProtoFixture.otpEntry(secret = null, name = "bad"),
            )
        )
        val result = MigrationPayloadParser.parseUri(uri(data))
        assertEquals(4, result.entries.size)
        assertEquals(1, result.importableCount)
        assertEquals(2, result.unsupportedCount)
        assertEquals(1, result.invalidCount)
    }

    @Test
    fun `unsupported raw digits value rejected`() {
        // Google only defines 0/1/2 for DigitCount; a raw value outside the
        // format (e.g. 5) is UNSUPPORTED.
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry(digits = 5)))
        val e = MigrationPayloadParser.parseUri(uri(data)).entries[0]
        assertEquals(MigrationEntryStatus.UNSUPPORTED, e.status)
        assertEquals("unsupported-digits", e.reason)
    }

    @Test
    fun `batch metadata parsed from protobuf`() {
        val data = ProtoFixture.payload(
            listOf(ProtoFixture.otpEntry()),
            batchSize = 4,
            batchIndex = 2,
            batchId = 99,
        )
        val result = MigrationPayloadParser.parseUri(uri(data))
        assertEquals(4, result.batchSize)
        assertEquals(2, result.batchIndex)
        assertEquals(99, result.batchId)
    }

    @Test
    fun `batch query params rejected unknown protocol parameter`() {
        // batch_* query extensions are NOT part of the real GA wire contract.
        // The strict URI contract only accepts `data=`. Appearing in the URI
        // → explicit malformed migration payload.
        val data = ProtoFixture.payload(
            listOf(ProtoFixture.otpEntry()),
            batchSize = 4,
            batchIndex = 2,
            batchId = 99,
        )
        assertMalformed(uri(data, "batch_size=3&batch_index=1&batch_id=7"), "unknown-query-parameter")
    }

    @Test
    fun `any unknown query parameter rejected`() {
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry()))
        assertMalformed(uri(data, "foo=bar"), "unknown-query-parameter")
        assertMalformed(uri(data, "batch_size=1"), "unknown-query-parameter")
    }

    @Test
    fun `missing data param rejected`() {
        assertThrows(MigrationPayloadParser.MigrationParseException::class.java) {
            MigrationPayloadParser.parseUri("otpauth-migration://offline?other=1")
        }
    }

    @Test
    fun `wrong scheme rejected`() {
        assertThrows(MigrationPayloadParser.MigrationParseException::class.java) {
            MigrationPayloadParser.parseUri("otpauth://totp/x")
        }
    }

    @Test
    fun `secret never exposed through status for unsupported entries`() {
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry(type = ProtoFixture.TYPE_HOTP)))
        val e = MigrationPayloadParser.parseUri(uri(data)).entries[0]
        assertNull(e.secretBase32)
    }

    @Test
    fun `toString never leaks the secret`() {
        val data = ProtoFixture.payload(
            listOf(ProtoFixture.otpEntry(secret = "SecretBytes123".toByteArray(Charsets.UTF_8)))
        )
        val e = MigrationPayloadParser.parseUri(uri(data)).entries[0]
        val rendered = e.toString()
        assertFalse(rendered.contains("JBSWY3DP"))
    }

    // ------------------------------------------------------------------
    // Strict Base64 / URI contract (final protocol convergence)
    // ------------------------------------------------------------------

    @Test
    fun `padded standard base64 with equals accepted`() {
        // Real Google Authenticator emits standard base64 with `=` padding
        // percent-encoded as %3D in the URI query.
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry()))
        val std = base64std(data)
        assertTrue(std.endsWith("="))
        val uri = "otpauth-migration://offline?data=${percentEncode(std)}"
        val result = MigrationPayloadParser.parseUri(uri)
        assertEquals(1, result.importableCount)
        assertEquals("alice@example.com", result.entries[0].name)
    }

    @Test
    fun `percent encoded plus and slash are accepted as standard base64`() {
        // Real Google Authenticator standard-base64 output contains `+` and
        // `/`; these MUST survive percent-decoding and be treated as Base64
        // alphabet, not as malformed characters.
        val std = InteropFixtures.ALPHABET_DISTINGUISHING_STANDARD
        assertTrue("standard base64 must contain + and / for this fixture", std.contains('+') && std.contains('/'))
        val uri = "otpauth-migration://offline?data=${percentEncode(std)}"
        val result = MigrationPayloadParser.parseUri(uri)
        assertEquals(1, result.importableCount)
        val e = result.entries[0]
        assertEquals("H-w-", e.name)
        assertEquals("I", e.issuer)
        assertEquals("SHA1", e.algorithm)
        assertEquals(6, e.digits)
        assertEquals("G2MGKTV7KIAKL6QJHG4Z26Q5PMUCX6BD", e.secretBase32)
    }

    @Test
    fun `raw standard base64 in data query accepted without percent encoding`() {
        // `+` `/` `=` passed raw (not percent-encoded) in the query still
        // decode correctly — the decoder treats them as standard Base64.
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry()))
        val std = base64std(data)
        assertTrue(std.endsWith("="))
        val result = MigrationPayloadParser.parseUri("otpauth-migration://offline?data=$std")
        assertEquals(1, result.importableCount)
        assertEquals("alice@example.com", result.entries[0].name)
    }

    @Test
    fun `urlsafe base64 payload rejected`() {
        // Base64URL (`-`/`_`) is NOT part of the verified Google Authenticator
        // wire contract → explicit malformed migration payload. Use the
        // alphabet-distinguishing fixture whose URL-safe form genuinely
        // contains `-`/`_`.
        val urlsafe = InteropFixtures.ALPHABET_DISTINGUISHING_URLSAFE
        assertTrue(urlsafe.contains('-') || urlsafe.contains('_'))
        assertMalformed(
            "otpauth-migration://offline?data=${percentEncode(urlsafe)}",
            "invalid-data-character",
        )
    }

    @Test
    fun `alphabet distinguishing urlsafe form rejected`() {
        // Same bytes whose URL-safe encoding genuinely differs from the
        // standard form must be REJECTED (standard accepted, URL-safe not).
        val std = InteropFixtures.ALPHABET_DISTINGUISHING_STANDARD
        val urlsafe = InteropFixtures.ALPHABET_DISTINGUISHING_URLSAFE
        assertTrue(std != urlsafe)
        // Standard (percent-encoded) → accepted.
        val fromStd = MigrationPayloadParser.parseUri("otpauth-migration://offline?data=${percentEncode(std)}")
        assertEquals(1, fromStd.importableCount)
        assertEquals("G2MGKTV7KIAKL6QJHG4Z26Q5PMUCX6BD", fromStd.entries[0].secretBase32)
        // URL-safe → rejected.
        assertMalformed(
            "otpauth-migration://offline?data=${percentEncode(urlsafe)}",
            "invalid-data-character",
        )
    }

    @Test
    fun `mixed standard and urlsafe alphabet rejected`() {
        // A payload that mixes `+`/`/` with `-`/`_` is not a single-alphabet
        // standard encoding → rejected (no mixed-alphabet normalization).
        // Use the alphabet-distinguishing fixture (standard form contains +
        // and /), then replace + with - to produce a genuinely mixed string.
        val std = InteropFixtures.ALPHABET_DISTINGUISHING_STANDARD
        assertTrue(std.contains('+'))
        val mixed = std.replace('+', '-')
        assertTrue(mixed.contains('-'))
        assertTrue(mixed.contains('/'))
        assertMalformed(
            "otpauth-migration://offline?data=${percentEncode(mixed)}",
            "invalid-data-character",
        )
    }

    @Test
    fun `no padding standard base64 rejected`() {
        // Real GA always emits RFC 4648 `=` padding. An unpadded form is not
        // part of the wire contract → explicit malformed migration payload
        // (rejected as `malformed-base64` by the length/padding check).
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry()))
        val std = base64std(data).trimEnd('=')
        assertFalse(std.endsWith("="))
        assertMalformed(
            "otpauth-migration://offline?data=${percentEncode(std)}",
            "malformed-base64",
        )
    }

    @Test
    fun `misplaced equals padding rejected`() {
        // `=` in the middle (ab=c) is not valid terminal padding.
        assertMalformed("otpauth-migration://offline?data=ab=c", "malformed-base64")
    }

    @Test
    fun `overlong padding rejected`() {
        // A data char count that cannot be represented with 4 chars of
        // padding (a===) is invalid for the RFC 4648 decoder.
        assertMalformed("otpauth-migration://offline?data=a===", "malformed-base64")
    }

    @Test
    fun `illegal base64 characters rejected`() {
        val ex = assertThrows(MigrationPayloadParser.MigrationParseException::class.java) {
            MigrationPayloadParser.parseUri("otpauth-migration://offline?data=!!!!")
        }
        assertEquals("invalid-data-character", ex.reason)
    }

    @Test
    fun `type unspecified treated as totp`() {
        // Google OtpType.UNSPECIFIED (0) is treated as TOTP by Google/Aegis/
        // ente; native v2 maps it to an importable 30s TOTP.
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry(type = ProtoFixture.TYPE_UNSPECIFIED)))
        val e = MigrationPayloadParser.parseUri(uri(data)).entries[0]
        assertEquals(MigrationEntryStatus.IMPORTABLE, e.status)
        assertEquals("SHA1", e.algorithm)
        assertEquals(6, e.digits)
        assertEquals(30, e.periodSeconds)
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
