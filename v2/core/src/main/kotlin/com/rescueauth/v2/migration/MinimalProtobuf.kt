package com.rescueauth.v2.migration

/**
 * Minimal, boundary-strict protobuf **wire** decoder (Phase 4 P2).
 *
 * This is NOT a general protobuf runtime. It implements exactly the small
 * subset of the protobuf wire format that the Google Authenticator
 * `otpauth-migration` payload needs, with a strict contract:
 *
 * - no reflection, no code generation, no dependency on a protobuf library
 *   (keeps `:core` a plain JVM module — zero new dependencies);
 * - field numbers are read as varint, wire types are validated
 *   (0=varint / 1=64-bit / 2=length-delimited / 5=32-bit; 3,4 deprecated
 *   groups and 6,7 reserved are rejected);
 * - unknown fields are skipped structurally (never guessed, never regexed);
 * - every read is bounds-checked so malformed bytes fail with a typed
 *   [MalformedPayloadException] instead of an index crash;
 * - lengths are validated against the remaining buffer before slicing;
 * - varints are limited to the 10-byte canonical encoding and reject
 *   overlong / non-canonical encodings where cheap to check.
 *
 * The decoder deliberately never exposes payload content through exceptions
 * or logs: errors carry a stable reason token only.
 */
internal object MinimalProtobuf {

    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_LENGTH_DELIMITED = 2
    const val WIRE_START_GROUP = 3
    const val WIRE_END_GROUP = 4
    const val WIRE_FIXED32 = 5

    /** A single parsed field: key (field number) + raw value view. */
    data class Field(
        val number: Int,
        val wireType: Int,
        /** varint value (only valid when [wireType] == [WIRE_VARINT]). */
        val varint: Long,
        /** byte range for length-delimited payloads. */
        val bytes: ByteArray,
    ) {
        fun asBytes(): ByteArray = bytes

        /** Length-delimited value decoded as a UTF-8 string. */
        fun asUtf8String(): String = String(bytes, Charsets.UTF_8)
    }

    class MalformedPayloadException(val reason: String) : Exception("malformed protobuf ($reason)")

    /**
     * Decodes a message body and invokes [onField] for every field in wire
     * order. Unknown fields are skipped, not surfaced.
     *
     * @throws MalformedPayloadException on any structural violation.
     */
    fun forEachField(body: ByteArray, onField: (Field) -> Unit) {
        var offset = 0
        while (offset < body.size) {
            val key = readVarint(body, offset)
            val keyLen = varintLength(body, offset)
            offset += keyLen

            val fieldNumber = (key ushr 3).toInt()
            val wireType = (key and 0x7).toInt()
            if (fieldNumber <= 0) throw MalformedPayloadException("invalid-field-number")
            when (wireType) {
                WIRE_VARINT -> {
                    val (value, len) = readVarintWithLength(body, offset)
                    offset += len
                    onField(Field(fieldNumber, wireType, value, ByteArray(0)))
                }
                WIRE_FIXED64 -> {
                    if (offset + 8 > body.size) throw MalformedPayloadException("truncated-fixed64")
                    offset += 8
                    onField(Field(fieldNumber, wireType, 0L, body.copyOfRange(offset - 8, offset)))
                }
                WIRE_LENGTH_DELIMITED -> {
                    val (len, lenSize) = readVarintWithLength(body, offset)
                    offset += lenSize
                    if (len < 0 || len > body.size - offset) {
                        throw MalformedPayloadException("invalid-length")
                    }
                    val slice = body.copyOfRange(offset, offset + len.toInt())
                    offset += len.toInt()
                    onField(Field(fieldNumber, wireType, 0L, slice))
                }
                WIRE_FIXED32 -> {
                    if (offset + 4 > body.size) throw MalformedPayloadException("truncated-fixed32")
                    offset += 4
                    onField(Field(fieldNumber, wireType, 0L, body.copyOfRange(offset - 4, offset)))
                }
                WIRE_START_GROUP, WIRE_END_GROUP ->
                    throw MalformedPayloadException("unsupported-group")
                else -> throw MalformedPayloadException("unsupported-wire-type")
            }
        }
    }

    /**
     * Parses the single top-level `otpauth-migration` payload message.
     *
     * ```
     * message MigrationPayload {
     *   repeated OtpParameters otp_parameters = 1;
     *   int32 version = 2;
     *   int32 batch_size = 3;
     *   int32 batch_index = 4;
     *   int32 batch_id = 5;
     * }
     * message OtpParameters {
     *   bytes secret = 1;
     *   string name = 2;
     *   string issuer = 3;
     *   int32 algorithm = 4;   // 0 = MD5, 1 = SHA1, 2 = SHA256, 3 = SHA512, 4 = SHA224
     *   int32 digits = 5;
     *   int32 type = 6;        // 0 = HOTP, 1 = TOTP
     *   int64 counter = 7;
     *   string otp_parameters_id = 8;
     * }
     * ```
     *
     * Returns the raw entries + batch metadata without interpreting any OTP
     * semantics; interpretation/validation belongs to [MigrationPayloadParser].
     */
    fun decodeMigrationPayload(body: ByteArray): MigrationWirePayload {
        val entries = ArrayList<MigrationWireEntry>()
        var version = 0
        var batchSize = 0
        var batchIndex = 0
        var batchId = 0
        forEachField(body) { field ->
            when (field.number) {
                1 -> {
                    if (field.wireType != WIRE_LENGTH_DELIMITED) {
                        throw MalformedPayloadException("bad-otp-entry")
                    }
                    entries += decodeOtpParameters(field.asBytes())
                }
                2 -> {
                    if (field.wireType != WIRE_VARINT) throw MalformedPayloadException("bad-version")
                    version = field.varint.toInt()
                }
                3 -> {
                    if (field.wireType != WIRE_VARINT) throw MalformedPayloadException("bad-batch-size")
                    batchSize = field.varint.toInt()
                }
                4 -> {
                    if (field.wireType != WIRE_VARINT) throw MalformedPayloadException("bad-batch-index")
                    batchIndex = field.varint.toInt()
                }
                5 -> {
                    if (field.wireType != WIRE_VARINT) throw MalformedPayloadException("bad-batch-id")
                    batchId = field.varint.toInt()
                }
                else -> Unit // unknown field: already structurally skipped
            }
        }
        return MigrationWirePayload(
            entries = entries,
            version = version,
            batchSize = batchSize,
            batchIndex = batchIndex,
            batchId = batchId,
        )
    }

    private fun decodeOtpParameters(bytes: ByteArray): MigrationWireEntry {
        var secret = ByteArray(0)
        var name: String? = null
        var issuer: String? = null
        var algorithm = 0
        var digits = 0
        var type = 0
        var counter = 0L
        var id: String? = null
        forEachField(bytes) { field ->
            when (field.number) {
                1 -> {
                    if (field.wireType != WIRE_LENGTH_DELIMITED) throw MalformedPayloadException("bad-secret")
                    secret = field.asBytes()
                }
                2 -> {
                    if (field.wireType != WIRE_LENGTH_DELIMITED) throw MalformedPayloadException("bad-name")
                    name = field.asUtf8String()
                }
                3 -> {
                    if (field.wireType != WIRE_LENGTH_DELIMITED) throw MalformedPayloadException("bad-issuer")
                    issuer = field.asUtf8String()
                }
                4 -> {
                    if (field.wireType != WIRE_VARINT) throw MalformedPayloadException("bad-algorithm")
                    algorithm = field.varint.toInt()
                }
                5 -> {
                    if (field.wireType != WIRE_VARINT) throw MalformedPayloadException("bad-digits")
                    digits = field.varint.toInt()
                }
                6 -> {
                    if (field.wireType != WIRE_VARINT) throw MalformedPayloadException("bad-type")
                    type = field.varint.toInt()
                }
                7 -> {
                    if (field.wireType != WIRE_VARINT) throw MalformedPayloadException("bad-counter")
                    counter = field.varint
                }
                8 -> {
                    if (field.wireType != WIRE_LENGTH_DELIMITED) throw MalformedPayloadException("bad-otp-id")
                    id = field.asUtf8String()
                }
                else -> Unit
            }
        }
        return MigrationWireEntry(
            secret = secret,
            name = name,
            issuer = issuer,
            algorithm = algorithm,
            digits = digits,
            type = type,
            counter = counter,
            id = id,
        )
    }

    // ------------------------------------------------------------------
    // Varint primitives
    // ------------------------------------------------------------------

    private fun varintLength(buffer: ByteArray, offset: Int): Int {
        var i = offset
        var count = 0
        while (true) {
            if (i >= buffer.size) throw MalformedPayloadException("truncated-varint")
            val b = buffer[i].toInt() and 0xff
            i++
            count++
            if (b and 0x80 == 0) return count
            if (count >= 10) throw MalformedPayloadException("overlong-varint")
        }
    }

    private fun readVarint(buffer: ByteArray, offset: Int): Long {
        var result = 0L
        var shift = 0
        var i = offset
        var count = 0
        while (true) {
            if (i >= buffer.size) throw MalformedPayloadException("truncated-varint")
            val b = buffer[i].toInt() and 0xff
            i++
            count++
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) {
                if (count > 10) throw MalformedPayloadException("overlong-varint")
                return result
            }
            shift += 7
            if (shift >= 64 || count >= 10) throw MalformedPayloadException("overlong-varint")
        }
    }

    private fun readVarintWithLength(buffer: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = offset
        var count = 0
        while (true) {
            if (i >= buffer.size) throw MalformedPayloadException("truncated-varint")
            val b = buffer[i].toInt() and 0xff
            i++
            count++
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) {
                if (count > 10) throw MalformedPayloadException("overlong-varint")
                return result to count
            }
            shift += 7
            if (shift >= 64 || count >= 10) throw MalformedPayloadException("overlong-varint")
        }
    }
}

/** Raw wire-level decode of an `otpauth-migration` payload (no OTP semantics). */
internal data class MigrationWirePayload(
    val entries: List<MigrationWireEntry>,
    val version: Int,
    val batchSize: Int,
    val batchIndex: Int,
    val batchId: Int,
)

/** Raw wire-level `OtpParameters` entry. */
internal data class MigrationWireEntry(
    val secret: ByteArray,
    val name: String?,
    val issuer: String?,
    val algorithm: Int,
    val digits: Int,
    val type: Int,
    val counter: Long,
    val id: String?,
)
