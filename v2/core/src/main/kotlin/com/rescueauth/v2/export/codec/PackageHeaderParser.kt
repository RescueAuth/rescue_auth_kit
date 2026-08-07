package com.rescueauth.v2.export.codec

/**
 * Parses and validates the untrusted v2 package header **before** any
 * expensive KDF work or large allocation happens.
 *
 * ## Security contract (THREAT_MODEL.md §Malicious header / §Header is untrusted)
 *
 * A hostile package may claim any KDF parameters / lengths. The parser:
 *
 * 1. Rejects wrong magic / unknown reserved flags / unsupported
 *    formatVersion → [PackageCodecException.UnsupportedFormat].
 * 2. Rejects unsupported cryptoVersion / algorithm ids →
 *    [PackageCodecException.UnsupportedCrypto].
 * 3. Rejects structurally broken / truncated / length-inconsistent headers →
 *    [PackageCodecException.MalformedPackage].
 * 4. Rejects KDF parameters outside the accepted decode range **before**
 *    Argon2id runs → [PackageCodecException.InvalidKdfParameters].
 *
 * The KDF is only invoked by the caller after [parse] has returned a fully
 * validated [PackageEnvelope].
 */
internal object PackageHeaderParser {

    private const val UINT32_MAX = 0xFFFFFFFFL
    private const val UINT16_MAX = 0xFFFF

    /**
     * Parses `bytes` (already bounded to [PackageFormat.MAX_PACKAGE_SIZE])
     * and returns the validated envelope plus the two AAD regions.
     *
     * @throws PackageCodecException on any invalid header.
     */
    fun parse(bytes: ByteArray): PackageEnvelope {
        if (bytes.size < PackageFormat.HEADER_FIXED_BYTES) {
            throw PackageCodecException.MalformedPackage(
                "package too short for fixed header (${bytes.size} < ${PackageFormat.HEADER_FIXED_BYTES})",
            )
        }

        // Magic.
        if (bytes.size < PackageFormat.MAGIC_BYTES ||
            String(bytes, 0, PackageFormat.MAGIC_BYTES, Charsets.US_ASCII) != PackageFormat.MAGIC
        ) {
            throw PackageCodecException.UnsupportedFormat("bad magic: not a v2 RescueAuth package")
        }

        val formatVersion = bytes[PackageLayout.OFF_FORMAT_VERSION].toInt() and 0xff
        if (formatVersion != PackageFormat.CURRENT_FORMAT_VERSION) {
            throw PackageCodecException.UnsupportedFormat(
                "unsupported formatVersion $formatVersion (supported: ${PackageFormat.CURRENT_FORMAT_VERSION})",
            )
        }

        val cryptoVersion = bytes[PackageLayout.OFF_CRYPTO_VERSION].toInt() and 0xff
        if (cryptoVersion != PackageFormat.CURRENT_CRYPTO_VERSION) {
            throw PackageCodecException.UnsupportedCrypto(
                "unsupported cryptoVersion $cryptoVersion (supported: ${PackageFormat.CURRENT_CRYPTO_VERSION})",
            )
        }

        val flags = bytes[PackageLayout.OFF_FLAGS].toInt() and 0xff
        if (flags != PackageFormat.FLAGS_NONE) {
            throw PackageCodecException.MalformedPackage("unsupported reserved header flags 0x${flags.toString(16)}")
        }

        // KDF algorithm id.
        val kdfAlgorithm = bytes[PackageLayout.OFF_KDF_ALGORITHM].toInt() and 0xff
        if (kdfAlgorithm != PackageFormat.KDF_ALGORITHM_ARGON2ID) {
            throw PackageCodecException.UnsupportedCrypto("unsupported KDF algorithm id $kdfAlgorithm")
        }

        // KDF parameters (uint32s are read as Long and range-checked).
        val memoryKiB = readUInt32(bytes, PackageLayout.OFF_KDF_MEMORY_KIB)
        val iterations = readUInt32(bytes, PackageLayout.OFF_KDF_ITERATIONS)
        val parallelism = bytes[PackageLayout.OFF_KDF_PARALLELISM].toInt() and 0xff
        val outputLength = bytes[PackageLayout.OFF_KDF_OUTPUT_LENGTH].toInt() and 0xff
        val saltLength = bytes[PackageLayout.OFF_KDF_SALT_LENGTH].toInt() and 0xff

        validateKdfParameters(memoryKiB, iterations, parallelism, outputLength, saltLength)

        // Salt region.
        val salt = sliceChecked(bytes, PackageLayout.OFF_KDF_SALT, saltLength, "kdfSalt")

        // Wrapping section.
        val wrapAlgorithm = bytes[saltStart(saltLength)].toInt() and 0xff
        if (wrapAlgorithm != PackageFormat.WRAP_ALGORITHM_XCHACHA20_POLY1305) {
            throw PackageCodecException.UnsupportedCrypto("unsupported wrapping algorithm id $wrapAlgorithm")
        }
        val wrapNonceLength = bytes[PackageLayout.wrapNonceLengthOffset(saltLength)].toInt() and 0xff
        if (wrapNonceLength != PackageFormat.XCHACHA20_NONCE_BYTES) {
            throw PackageCodecException.MalformedPackage(
                "wrapping nonce must be ${PackageFormat.XCHACHA20_NONCE_BYTES} bytes, got $wrapNonceLength",
            )
        }
        val wrapNonce = sliceChecked(
            bytes, PackageLayout.wrapNonceOffset(saltLength), wrapNonceLength, "wrapping nonce",
        )
        val wrappedKeyLength = readUInt16(bytes, PackageLayout.wrappedKeyLengthOffset(saltLength))
        if (wrappedKeyLength > PackageFormat.MAX_WRAPPED_KEY_BYTES) {
            throw PackageCodecException.MalformedPackage(
                "wrapped PackageKey too large: $wrappedKeyLength > ${PackageFormat.MAX_WRAPPED_KEY_BYTES}",
            )
        }
        val wrappedKey = sliceChecked(
            bytes, PackageLayout.wrappedKeyOffset(saltLength), wrappedKeyLength, "wrapped PackageKey",
        )

        // Payload section.
        val payloadAlgorithm = bytes[PackageLayout.payloadAlgorithmOffset(saltLength, wrappedKeyLength)].toInt() and 0xff
        if (payloadAlgorithm != PackageFormat.PAYLOAD_ALGORITHM_XCHACHA20_POLY1305) {
            throw PackageCodecException.UnsupportedCrypto("unsupported payload algorithm id $payloadAlgorithm")
        }
        val payloadNonceLength = bytes[
            PackageLayout.payloadNonceLengthOffset(saltLength, wrappedKeyLength)
        ].toInt() and 0xff
        if (payloadNonceLength != PackageFormat.XCHACHA20_NONCE_BYTES) {
            throw PackageCodecException.MalformedPackage(
                "payload nonce must be ${PackageFormat.XCHACHA20_NONCE_BYTES} bytes, got $payloadNonceLength",
            )
        }
        val payloadNonce = sliceChecked(
            bytes, PackageLayout.payloadNonceOffset(saltLength, wrappedKeyLength), payloadNonceLength, "payload nonce",
        )
        val payloadCiphertextLength = readUInt32(
            bytes, PackageLayout.payloadCiphertextLengthOffset(saltLength, wrappedKeyLength),
        )
        if (payloadCiphertextLength > PackageFormat.MAX_PAYLOAD_CIPHERTEXT_SIZE.toLong()) {
            throw PackageCodecException.MalformedPackage(
                "payload ciphertext too large: $payloadCiphertextLength > " +
                    "${PackageFormat.MAX_PAYLOAD_CIPHERTEXT_SIZE}",
            )
        }
        val payloadEnd = headerEnd(saltLength, wrappedKeyLength) + payloadCiphertextLength
        if (payloadEnd > bytes.size) {
            throw PackageCodecException.MalformedPackage(
                "truncated package: header+payload claim $payloadEnd bytes, have ${bytes.size}",
            )
        }
        val payloadCiphertext = sliceChecked(
            bytes, headerEnd(saltLength, wrappedKeyLength), payloadCiphertextLength.toInt(), "payload ciphertext",
        )

        // Trailing garbage is rejected: the payload must end exactly at EOF.
        if (payloadEnd != bytes.size.toLong()) {
            throw PackageCodecException.MalformedPackage(
                "trailing garbage: package has ${bytes.size - payloadEnd} bytes after the payload",
            )
        }

        // Cross-check the headerLength field (if present) so a self-inconsistent
        // header cannot be silently accepted under a different layout.
        val declaredHeaderLength = readUInt32(bytes, PackageLayout.OFF_HEADER_LENGTH)
        val computedHeaderLength = PackageLayout.headerLengthValue(saltLength, wrappedKeyLength).toLong()
        if (declaredHeaderLength != computedHeaderLength) {
            throw PackageCodecException.MalformedPackage(
                "headerLength mismatch: declared $declaredHeaderLength, computed $computedHeaderLength",
            )
        }

        val headerAad = bytes.copyOfRange(0, headerEnd(saltLength, wrappedKeyLength))
        val wrapAad = bytes.copyOfRange(0, PackageLayout.wrapAadEnd(saltLength))

        return PackageEnvelope(
            formatVersion = formatVersion,
            cryptoVersion = cryptoVersion,
            flags = flags,
            kdfAlgorithm = kdfAlgorithm,
            kdfMemoryKiB = memoryKiB.toInt(),
            kdfIterations = iterations.toInt(),
            kdfParallelism = parallelism,
            kdfOutputLength = outputLength,
            salt = salt,
            wrapAlgorithm = wrapAlgorithm,
            wrapNonce = wrapNonce,
            wrappedKey = wrappedKey,
            payloadAlgorithm = payloadAlgorithm,
            payloadNonce = payloadNonce,
            payloadCiphertext = payloadCiphertext,
            headerAadBytes = headerAad,
            wrapAadBytes = wrapAad,
        )
    }

    // ------------------------------------------------------------------
    // KDF parameter validation (PRE-KDF, no allocation)
    // ------------------------------------------------------------------

    private fun validateKdfParameters(
        memoryKiB: Long,
        iterations: Long,
        parallelism: Int,
        outputLength: Int,
        saltLength: Int,
    ) {
        // memoryKiB must be at least 8 * parallelism (Argon2 constraint) and
        // within the accepted decode range. Read as Long first to avoid overflow.
        if (memoryKiB < PackageFormat.MIN_MEMORY_KIB || memoryKiB > PackageFormat.MAX_MEMORY_KIB) {
            throw PackageCodecException.InvalidKdfParameters(
                "kdf memoryKiB out of safe range: $memoryKiB (accepted ${PackageFormat.MIN_MEMORY_KIB}.." +
                    "${PackageFormat.MAX_MEMORY_KIB})",
            )
        }
        if (iterations < PackageFormat.MIN_ITERATIONS || iterations > PackageFormat.MAX_ITERATIONS) {
            throw PackageCodecException.InvalidKdfParameters(
                "kdf iterations out of safe range: $iterations (accepted ${PackageFormat.MIN_ITERATIONS}.." +
                    "${PackageFormat.MAX_ITERATIONS})",
            )
        }
        if (parallelism < PackageFormat.MIN_PARALLELISM || parallelism > PackageFormat.MAX_PARALLELISM) {
            throw PackageCodecException.InvalidKdfParameters(
                "kdf parallelism out of safe range: $parallelism (accepted " +
                    "${PackageFormat.MIN_PARALLELISM}..${PackageFormat.MAX_PARALLELISM})",
            )
        }
        if (memoryKiB < 8L * parallelism) {
            throw PackageCodecException.InvalidKdfParameters(
                "kdf memoryKiB $memoryKiB too small for parallelism $parallelism (Argon2 constraint)",
            )
        }
        if (outputLength < PackageFormat.MIN_OUTPUT_LENGTH || outputLength > PackageFormat.MAX_OUTPUT_LENGTH) {
            throw PackageCodecException.InvalidKdfParameters(
                "kdf outputLength out of safe range: $outputLength (accepted " +
                    "${PackageFormat.MIN_OUTPUT_LENGTH}..${PackageFormat.MAX_OUTPUT_LENGTH})",
            )
        }
        if (saltLength < PackageFormat.MIN_SALT_BYTES || saltLength > PackageFormat.MAX_SALT_BYTES) {
            throw PackageCodecException.InvalidKdfParameters(
                "kdf salt length out of safe range: $saltLength (accepted " +
                    "${PackageFormat.MIN_SALT_BYTES}..${PackageFormat.MAX_SALT_BYTES})",
            )
        }
    }

    // ------------------------------------------------------------------
    // Overflow-safe helpers
    // ------------------------------------------------------------------

    /** Reads a big-endian uint32 as a Long (no sign-extension / overflow). */
    private fun readUInt32(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) {
            throw PackageCodecException.MalformedPackage("truncated uint32 at offset $offset")
        }
        return ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)
    }

    /** Reads a big-endian uint16 as an Int. */
    private fun readUInt16(bytes: ByteArray, offset: Int): Int {
        if (offset + 2 > bytes.size) {
            throw PackageCodecException.MalformedPackage("truncated uint16 at offset $offset")
        }
        return ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    }

    /** Slices [len] bytes at [offset], rejecting truncation. */
    private fun sliceChecked(bytes: ByteArray, offset: Int, len: Int, what: String): ByteArray {
        if (len < 0) throw PackageCodecException.MalformedPackage("negative length for $what")
        if (offset < 0 || offset + len > bytes.size) {
            throw PackageCodecException.MalformedPackage(
                "$what region out of bounds: offset=$offset len=$len size=${bytes.size}",
            )
        }
        return bytes.copyOfRange(offset, offset + len)
    }

    /** Byte offset where the wrapping section begins (right after the salt). */
    private fun saltStart(saltLength: Int): Int = PackageLayout.OFF_KDF_SALT + saltLength

    /** Byte index where the payload ciphertext begins. */
    private fun headerEnd(saltLength: Int, wrappedKeyLength: Int): Int =
        PackageLayout.headerEnd(saltLength, wrappedKeyLength)
}
