package com.rescueauth.v2.scanner

import java.util.Base64

/**
 * Minimal otpauth-migration payload builder for app-layer tests (Phase 4 P2).
 * Produces the same wire format as the core ProtoFixture so the app scanner /
 * ViewModel tests can exercise real synthetic migration URIs without a
 * protobuf library or any real Google Authenticator credential.
 */
internal object MigrationTestFixtures {

    fun varint(value: Long): ByteArray {
        var v = value
        val out = ArrayList<Byte>()
        while (true) {
            val b = (v and 0x7f).toInt()
            v = v ushr 7
            if (v == 0L) {
                out += b.toByte()
                break
            }
            out += (b or 0x80).toByte()
        }
        return out.toByteArray()
    }

    fun key(fieldNumber: Int, wireType: Int): ByteArray =
        varint((fieldNumber.toLong() shl 3) or wireType.toLong())

    fun lengthDelimited(fieldNumber: Int, payload: ByteArray): ByteArray =
        key(fieldNumber, 2) + varint(payload.size.toLong()) + payload

    fun varintField(fieldNumber: Int, value: Long): ByteArray =
        key(fieldNumber, 0) + varint(value)

    fun stringField(fieldNumber: Int, value: String): ByteArray =
        lengthDelimited(fieldNumber, value.toByteArray(Charsets.UTF_8))

    fun bytesField(fieldNumber: Int, value: ByteArray): ByteArray =
        lengthDelimited(fieldNumber, value)

    fun otpEntry(
        secret: ByteArray = "Hello!".toByteArray(Charsets.UTF_8),
        name: String? = "alice@example.com",
        issuer: String? = "GitHub",
        algorithm: Int = 1,
        digits: Int = 6,
        type: Int = 1,
    ): ByteArray {
        var body = ByteArray(0)
        body += bytesField(1, secret)
        if (name != null) body += stringField(2, name)
        if (issuer != null) body += stringField(3, issuer)
        body += varintField(4, algorithm.toLong())
        body += varintField(5, digits.toLong())
        body += varintField(6, type.toLong())
        return lengthDelimited(1, body)
    }

    fun payload(
        entries: List<ByteArray>,
        version: Int = 1,
        batchSize: Int = 0,
        batchIndex: Int = 0,
        batchId: Int = 0,
    ): ByteArray {
        var body = ByteArray(0)
        for (e in entries) body += e
        if (version != 0) body += varintField(2, version.toLong())
        if (batchSize != 0) body += varintField(3, batchSize.toLong())
        if (batchIndex != 0) body += varintField(4, batchIndex.toLong())
        if (batchId != 0) body += varintField(5, batchId.toLong())
        return body
    }

    fun migrationUri(
        entries: List<ByteArray>,
        batchSize: Int = 0,
        batchIndex: Int = 0,
        batchId: Int = 0,
        query: String = "",
    ): String {
        val data = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload(entries, batchSize = batchSize, batchIndex = batchIndex, batchId = batchId))
        var uri = "otpauth-migration://offline?data=$data"
        if (query.isNotEmpty()) uri += "&$query"
        return uri
    }
}
