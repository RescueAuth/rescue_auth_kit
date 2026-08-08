package com.rescueauth.v2.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Pure-core tests for the otpauth-migration payload parser (synthetic fixtures). */
class MigrationPayloadParserTest {

    private fun base64url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun uri(data: ByteArray, query: String = ""): String {
        val base = "otpauth-migration://offline?data=${base64url(data)}"
        return if (query.isEmpty()) base else "$base&$query"
    }

    // RFC 4648 test vectors for raw-secret -> Base32 (no padding).
    private fun base32(bytes: ByteArray): String = MigrationPayloadParser.encodeBase32NoPadding(bytes)

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
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry(algorithm = 1)))
        assertEquals("SHA1", MigrationPayloadParser.parseUri(uri(data)).entries[0].algorithm)
    }

    @Test
    fun `06 sha256 algorithm`() {
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry(algorithm = 2)))
        assertEquals("SHA256", MigrationPayloadParser.parseUri(uri(data)).entries[0].algorithm)
    }

    @Test
    fun `07 sha512 algorithm`() {
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry(algorithm = 3)))
        assertEquals("SHA512", MigrationPayloadParser.parseUri(uri(data)).entries[0].algorithm)
    }

    @Test
    fun `08 supported digits carried`() {
        for (digits in listOf(6, 7, 8, 9, 10)) {
            val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry(digits = digits)))
            val e = MigrationPayloadParser.parseUri(uri(data)).entries[0]
            assertEquals(digits, e.digits)
        }
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
        // Valid base64 but garbage bytes: 0xFF 0xFF is not a valid message.
        val bad = "otpauth-migration://offline?data=${base64url(byteArrayOf(0xff.toByte(), 0xff.toByte()))}"
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
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry(type = 0)))
        val result = MigrationPayloadParser.parseUri(uri(data))
        val e = result.entries[0]
        assertEquals(MigrationEntryStatus.UNSUPPORTED, e.status)
        assertEquals("hotp-not-supported", e.reason)
    }

    @Test
    fun `13 unsupported algorithm marked unsupported`() {
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry(algorithm = 0))) // MD5
        val e = MigrationPayloadParser.parseUri(uri(data)).entries[0]
        assertEquals(MigrationEntryStatus.UNSUPPORTED, e.status)
        assertEquals("unsupported-algorithm", e.reason)
        // SHA224 also unsupported
        val data2 = ProtoFixture.payload(listOf(ProtoFixture.otpEntry(algorithm = 4)))
        assertEquals(MigrationEntryStatus.UNSUPPORTED, MigrationPayloadParser.parseUri(uri(data2)).entries[0].status)
    }

    @Test
    fun `14 mixed valid plus unsupported entries`() {
        val data = ProtoFixture.payload(
            listOf(
                ProtoFixture.otpEntry(name = "good", issuer = "GitHub"),
                ProtoFixture.otpEntry(name = "hotp", issuer = "X", type = 0),
                ProtoFixture.otpEntry(name = "md5", issuer = "Y", algorithm = 0),
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
    fun `unsupported digits marked unsupported`() {
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
    fun `batch metadata query wins over protobuf`() {
        val data = ProtoFixture.payload(
            listOf(ProtoFixture.otpEntry()),
            batchSize = 4,
            batchIndex = 2,
            batchId = 99,
        )
        val result = MigrationPayloadParser.parseUri(uri(data, "batch_size=3&batch_index=1&batch_id=7"))
        assertEquals(3, result.batchSize)
        assertEquals(1, result.batchIndex)
        assertEquals(7, result.batchId)
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
    fun `invalid batch query values fall back to protobuf defaults`() {
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry()), batchSize = 0)
        val result = MigrationPayloadParser.parseUri(uri(data, "batch_size=abc"))
        assertEquals(0, result.batchSize)
    }

    @Test
    fun `secret never exposed through status for unsupported entries`() {
        val data = ProtoFixture.payload(listOf(ProtoFixture.otpEntry(type = 0)))
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
}
