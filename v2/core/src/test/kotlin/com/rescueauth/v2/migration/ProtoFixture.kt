package com.rescueauth.v2.migration

/**
 * Synthetic protobuf wire builder for tests (Phase 4 P2).
 *
 * Builds the exact `MigrationPayload` / `OtpParameters` wire structure that
 * Google Authenticator's `otpauth-migration` format uses, from raw bytes, so
 * the parser can be tested without a protobuf library and without any real
 * Google Authenticator credential.
 */
internal object ProtoFixture {

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

    fun key(fieldNumber: Int, wireType: Int): ByteArray = varint(((fieldNumber.toLong() shl 3) or wireType.toLong()))

    fun lengthDelimited(fieldNumber: Int, payload: ByteArray): ByteArray {
        val k = key(fieldNumber, 2)
        val len = varint(payload.size.toLong())
        return k + len + payload
    }

    fun varintField(fieldNumber: Int, value: Long): ByteArray =
        key(fieldNumber, 0) + varint(value)

    fun stringField(fieldNumber: Int, value: String): ByteArray =
        lengthDelimited(fieldNumber, value.toByteArray(Charsets.UTF_8))

    fun bytesField(fieldNumber: Int, value: ByteArray): ByteArray =
        lengthDelimited(fieldNumber, value)

    fun otpEntry(
        secret: ByteArray? = byteArrayOf(
            0x48, 0x65, 0x6c, 0x6c, 0x6f, 0x21, 0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte(),
        ),
        name: String? = "alice@example.com",
        issuer: String? = "GitHub",
        algorithm: Int = 1,
        digits: Int = 6,
        type: Int = 1,
        counter: Long = 0,
        id: String? = null,
    ): ByteArray {
        var body = ByteArray(0)
        if (secret != null) body += bytesField(1, secret)
        if (name != null) body += stringField(2, name)
        if (issuer != null) body += stringField(3, issuer)
        body += varintField(4, algorithm.toLong())
        body += varintField(5, digits.toLong())
        body += varintField(6, type.toLong())
        if (counter != 0L) body += varintField(7, counter)
        if (id != null) body += stringField(8, id)
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
}
