package com.rescueauth.v2.export.codec

import com.rescueauth.v2.export.PackageValidator
import com.rescueauth.v2.export.VaultPackagePayload
import java.io.ByteArrayOutputStream

/**
 * PortablePackageCodec — the Phase 3B encrypted package codec.
 *
 * A single codec handles **every** snapshot scope (FULL_VAULT /
 * AUTHENTICATOR_ONLY / DEVELOPER_ONLY / SELECTED_ITEMS). What goes into the
 * encrypted payload is decided by the logical payload
 * ([VaultPackagePayload] / `SnapshotScope`) — there is no separate
 * "FullBackupCodec" / "SelectiveBackupCodec" (ROADMAP §8.4 /
 * PACKAGE_FORMAT.md §Selective snapshot).
 *
 * ## API boundary (PACKAGE_FORMAT.md §API)
 *
 * This is a pure Kotlin/JVM codec: no Android Context, Room, SAF, Uri,
 * Activity or BiometricPrompt anywhere. It can be fully validated by JVM
 * tests.
 *
 * ## Pipeline
 *
 * **encode**
 * ```
 * VaultPackagePayload
 *   → validate (logical)
 *   → serialize (JSON)
 *   → random 256-bit PackageKey
 *   → random salt → Argon2id(PIN) → KEK
 *   → wrap PackageKey (XChaCha20-Poly1305, AAD = header prefix up to wrapped key)
 *   → AEAD-encrypt serialized payload (AAD = full header prefix)
 *   → envelope bytes
 * ```
 *
 * **decode**
 * ```
 * package bytes
 *   → parse+validate untrusted header (before KDF)
 *   → Argon2id(PIN, header params) → KEK
 *   → unwrap PackageKey (AEAD, AAD = header prefix up to wrapped key)
 *   → decrypt payload (AEAD, AAD = full header prefix)
 *   → deserialize
 *   → PackageValidator.validate
 *   → VaultPackagePayload
 * ```
 *
 * Every random material (salt / PackageKey / wrapping nonce / payload nonce)
 * is regenerated per export, so exporting the same payload with the same PIN
 * twice yields different package bytes.
 *
 * ## Error taxonomy
 *
 * see [PackageCodecException].
 *
 * ## Zeroization
 *
 * Best-effort zeroization (see [PackageCrypto.zeroize]) of the PIN bytes,
 * KEK, unwrapped PackageKey and plaintext payload after use.
 */
object PortablePackageCodec {

    /**
     * Encodes a validated [payload] into portable package bytes.
     *
     * @param pin per-export PIN (UTF-8). The codec copies it into a
     *   [ByteArray] for the KDF and zeroizes it afterwards; the caller's
     *   [String] itself is not retained by the codec.
     */
    fun encode(payload: VaultPackagePayload, pin: String): ByteArray {
        // 1. Logical validation BEFORE any crypto work (fail fast, deterministic).
        PackageValidator.validate(payload)
        val pinBytes = pin.toByteArray(Charsets.UTF_8)
        return try {
            encodeCore(payload, pinBytes)
        } finally {
            PackageCrypto.zeroize(pinBytes)
        }
    }

    /**
     * Encodes with a caller-owned [CharArray] PIN. The codec copies the
     * characters into an internal [ByteArray] for the KDF and zeroizes it
     * afterwards (best-effort; see [PackageCrypto.zeroize]). The caller's
     * [CharArray] itself is left untouched — callers that want to wipe it may
     * do so after [encode] returns.
     */
    fun encode(payload: VaultPackagePayload, pin: CharArray): ByteArray {
        PackageValidator.validate(payload)
        val bytes = String(pin).toByteArray(Charsets.UTF_8)
        return try {
            encodeCore(payload, bytes)
        } finally {
            PackageCrypto.zeroize(bytes)
        }
    }

    /**
     * Encodes with a caller-owned [ByteArray] PIN (UTF-8). The codec copies
     * the bytes into an internal buffer for the KDF and zeroizes it after use;
     * the caller's array is left untouched.
     */
    fun encode(payload: VaultPackagePayload, pin: ByteArray): ByteArray {
        PackageValidator.validate(payload)
        val copy = pin.copyOf()
        return try {
            encodeCore(payload, copy)
        } finally {
            PackageCrypto.zeroize(copy)
        }
    }

    /**
     * Test-only entry that mirrors [encode] but skips [PackageValidator].
     *
     * Used to build a cryptographically-valid package whose logical payload is
     * invalid (so the decode path's own [PackageValidator] invocation can be
     * exercised). NOT part of the production API.
     */
    @PublishedApi
    internal fun encodeWithoutValidation(payload: VaultPackagePayload, pin: String): ByteArray {
        val bytes = pin.toByteArray(Charsets.UTF_8)
        return try {
            encodeCore(payload, bytes)
        } finally {
            PackageCrypto.zeroize(bytes)
        }
    }

    /**
     * Test-only entry that encodes with explicit KDF parameters.
     *
     * Used to prove that [decode] reads the parameters stored in the package
     * header (supporting future stronger KDF params) instead of hardcoding the
     * encode-time defaults (PACKAGE_FORMAT.md §KDF policy). NOT part of the
     * production API — production always encodes with [PackageFormat] defaults.
     */
    @PublishedApi
    internal fun encodeWithKdfParams(
        payload: VaultPackagePayload,
        pin: String,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int,
        outputLength: Int,
    ): ByteArray {
        val bytes = pin.toByteArray(Charsets.UTF_8)
        return try {
            encodeCore(payload, bytes, memoryKiB, iterations, parallelism, outputLength)
        } finally {
            PackageCrypto.zeroize(bytes)
        }
    }

    private fun encodeCore(
        payload: VaultPackagePayload,
        pinBytes: ByteArray,
        memoryKiB: Int = PackageFormat.DEFAULT_MEMORY_KIB,
        iterations: Int = PackageFormat.DEFAULT_ITERATIONS,
        parallelism: Int = PackageFormat.DEFAULT_PARALLELISM,
        outputLength: Int = PackageFormat.DEFAULT_OUTPUT_LENGTH,
    ): ByteArray {
        // 2. Serialize the logical payload (platform-neutral JSON).
        val plaintext = PayloadJson.encode(payload)
        try {
            // 2a. Exact post-serialization capacity guard (defense-in-depth).
            // The logical validator already rejects over-budget payloads via
            // PackageCapacity; this exact check makes an over-limit encode fail
            // EXPLICITLY with PackageTooLarge before any oversized allocation —
            // never OOM (PACKAGE_FORMAT.md §Capacity).
            if (plaintext.size > PackageFormat.MAX_SERIALIZED_PAYLOAD_SIZE) {
                throw PackageCodecException.PackageTooLarge(
                    "serialized payload is ${plaintext.size} bytes, exceeding the package budget " +
                        "${PackageFormat.MAX_SERIALIZED_PAYLOAD_SIZE} bytes (16 MiB package cap minus header/tag)",
                )
            }
            // 3. Fresh random material per export.
            val salt = PackageCrypto.newSalt()
            val wrapNonce = PackageCrypto.newNonce()
            val payloadNonce = PackageCrypto.newNonce()
            val packageKey = PackageCrypto.newPackageKey()

            // The payload ciphertext region = plaintext + 16-byte AEAD tag
            // (XChaCha20-Poly1305 is a stream cipher with a fixed tag; the
            // header carries the ciphertext length, not the plaintext length).
            val payloadCiphertextLength = plaintext.size + PackageFormat.XCHACHA20_TAG_BYTES

            // 4. Derive the wrapping key (KEK) from PIN bytes + salt.
            val kek = try {
                PackageCrypto.argon2id(
                    pinBytes,
                    salt,
                    memoryKiB,
                    iterations,
                    parallelism,
                    outputLength,
                )
            } finally {
                PackageCrypto.zeroize(pinBytes)
            }

            // 5. Header prefix WITHOUT the wrapped key (used as the wrap AAD).
            val headerWithoutWrappedKey = buildHeaderPrefix(
                salt, wrapNonce, payloadNonce, payloadCiphertextLength,
                memoryKiB, iterations, parallelism, outputLength,
            )
            val wrapAad = headerWithoutWrappedKey.copyOfRange(
                0,
                PackageLayout.wrapAadEnd(salt.size),
            )

            // 6. Wrap the PackageKey (AAD = header prefix up to the wrapped key).
            val wrappedKey = try {
                PackageCrypto.aeadEncrypt(kek, wrapNonce, wrapAad, packageKey)
            } finally {
                PackageCrypto.zeroize(kek)
            }

            // 7. Full header prefix WITH the real wrapped key = the payload AEAD AAD.
            val fullHeader = buildFullHeader(headerWithoutWrappedKey, salt.size, wrappedKey)
            val payloadCiphertext = try {
                PackageCrypto.aeadEncrypt(packageKey, payloadNonce, fullHeader, plaintext)
            } finally {
                PackageCrypto.zeroize(packageKey)
            }
            check(payloadCiphertext.size == payloadCiphertextLength) {
                "payload ciphertext length mismatch: ${payloadCiphertext.size} != $payloadCiphertextLength"
            }

            // 8. Assemble the final package bytes.
            return assemble(fullHeader, payloadCiphertext)
        } finally {
            PackageCrypto.zeroize(plaintext)
        }
    }

    /**
     * Decodes portable package bytes back into a logically validated
     * [VaultPackagePayload].
     *
     * @param pin the per-export PIN (UTF-8).
     * @throws PackageCodecException on any parse / auth / validation failure.
     */
    fun decode(packageBytes: ByteArray, pin: String): VaultPackagePayload {
        val pinBytes = pin.toByteArray(Charsets.UTF_8)
        return try {
            decodeCore(packageBytes, pinBytes)
        } finally {
            PackageCrypto.zeroize(pinBytes)
        }
    }

    /**
     * Decodes with a caller-owned [CharArray] PIN (converted to bytes for the
     * KDF and zeroized afterwards — best-effort).
     */
    fun decode(packageBytes: ByteArray, pin: CharArray): VaultPackagePayload {
        val pinBytes = String(pin).toByteArray(Charsets.UTF_8)
        return try {
            decodeCore(packageBytes, pinBytes)
        } finally {
            PackageCrypto.zeroize(pinBytes)
        }
    }

    /**
     * Decodes with a caller-owned [ByteArray] PIN (UTF-8). The codec copies
     * the bytes and zeroizes the copy after use; the caller's array is left
     * untouched.
     */
    fun decode(packageBytes: ByteArray, pin: ByteArray): VaultPackagePayload {
        val pinCopy = pin.copyOf()
        return try {
            decodeCore(packageBytes, pinCopy)
        } finally {
            PackageCrypto.zeroize(pinCopy)
        }
    }

    private fun decodeCore(packageBytes: ByteArray, pinBytes: ByteArray): VaultPackagePayload {
        // 1. Size gate before parsing (bounds allocation).
        if (packageBytes.size > PackageFormat.MAX_PACKAGE_SIZE) {
            throw PackageCodecException.MalformedPackage(
                "package too large: ${packageBytes.size} > ${PackageFormat.MAX_PACKAGE_SIZE}",
            )
        }

        // 2. Parse + validate the untrusted header (before KDF, before big allocations).
        val envelope = PackageHeaderParser.parse(packageBytes)

        // 2a. RUNTIME DECODE RESOURCE POLICY — reject parameters that are
        // structurally valid and inside the format hard limits but above the
        // mobile decode budget, BEFORE Argon2id runs (PACKAGE_FORMAT.md
        // §Runtime decode resource policy). The default encode parameters
        // (19 MiB / 2 iterations) always pass.
        PackageRuntimePolicy.checkDecodeBudget(
            envelope.kdfMemoryKiB,
            envelope.kdfIterations,
            envelope.kdfParallelism,
        )

        // 3. Derive the wrapping key from the header's KDF parameters.
        val kek = try {
            PackageCrypto.argon2id(
                pinBytes,
                envelope.salt,
                envelope.kdfMemoryKiB,
                envelope.kdfIterations,
                envelope.kdfParallelism,
                envelope.kdfOutputLength,
            )
        } finally {
            PackageCrypto.zeroize(pinBytes)
        }

        // 4. Unwrap the PackageKey (AAD = header prefix up to the wrapped key).
        val packageKey = try {
            PackageCrypto.aeadDecrypt(kek, envelope.wrapNonce, envelope.wrapAadBytes, envelope.wrappedKey)
        } finally {
            PackageCrypto.zeroize(kek)
        }
        if (packageKey.size != PackageFormat.PACKAGE_KEY_BYTES) {
            PackageCrypto.zeroize(packageKey)
            throw PackageCodecException.MalformedPackage(
                "unwrapped PackageKey has unexpected length ${packageKey.size}",
            )
        }

        // 5. Decrypt the payload (AAD = full header prefix carried by the envelope).
        val plaintext = try {
            PackageCrypto.aeadDecrypt(packageKey, envelope.payloadNonce, envelope.headerAadBytes, envelope.payloadCiphertext)
        } finally {
            PackageCrypto.zeroize(packageKey)
        }

        // 6. Deserialize + logical validation. Any failure returns NO partial payload.
        try {
            val payload = PayloadJson.decode(plaintext)
            PackageValidator.validate(payload)
            return payload
        } catch (e: PackageCodecException) {
            throw e
        } catch (e: Exception) {
            throw PackageCodecException.LogicalPayloadInvalid(
                "malformed or invalid logical payload: ${e.message}",
                e,
            )
        } finally {
            PackageCrypto.zeroize(plaintext)
        }
    }

    // ------------------------------------------------------------------
    // Envelope assembly
    // ------------------------------------------------------------------

    /**
     * Builds the fixed-size header prefix for the current format/crypto
     * version. The wrapped-key region is a zero placeholder (its length is
     * carried by the `wrappedKeyLength` field) and the payload ciphertext
     * length field carries the real serialized size.
     *
     * Returns the header bytes from magic up to (but excluding) the payload
     * ciphertext region, with a placeholder for the wrapped key.
     */
    private fun buildHeaderPrefix(
        salt: ByteArray,
        wrapNonce: ByteArray,
        payloadNonce: ByteArray,
        payloadCiphertextLength: Int,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int,
        outputLength: Int,
    ): ByteArray {
        val wrappedKeyLength = PackageFormat.WRAPPED_PACKAGE_KEY_BYTES
        val saltLength = salt.size
        val headerLength = PackageLayout.headerLengthValue(saltLength, wrappedKeyLength)

        val out = ByteArrayOutputStream()
        out.write(PackageFormat.MAGIC.toByteArray(Charsets.US_ASCII))
        out.write(PackageFormat.CURRENT_FORMAT_VERSION)
        out.write(PackageFormat.CURRENT_CRYPTO_VERSION)
        out.write(PackageFormat.FLAGS_NONE)
        writeUInt32(out, headerLength.toLong())
        out.write(PackageFormat.DEFAULT_KDF_ALGORITHM)
        writeUInt32(out, memoryKiB.toLong())
        writeUInt32(out, iterations.toLong())
        out.write(parallelism)
        out.write(outputLength)
        out.write(saltLength)
        out.write(salt)
        out.write(PackageFormat.WRAP_ALGORITHM_XCHACHA20_POLY1305)
        out.write(wrapNonce.size)
        out.write(wrapNonce)
        writeUInt16(out, wrappedKeyLength)
        out.write(ByteArray(wrappedKeyLength)) // placeholder; filled by buildFullHeader()
        out.write(PackageFormat.PAYLOAD_ALGORITHM_XCHACHA20_POLY1305)
        out.write(payloadNonce.size)
        out.write(payloadNonce)
        writeUInt32(out, payloadCiphertextLength.toLong())
        return out.toByteArray()
    }

    /**
     * Replaces the wrapped-key placeholder inside [headerWithoutWrappedKey]
     * with the real [wrappedKey], returning the full header prefix that the
     * payload AEAD authenticates.
     */
    private fun buildFullHeader(
        headerWithoutWrappedKey: ByteArray,
        saltLength: Int,
        wrappedKey: ByteArray,
    ): ByteArray {
        val wrappedKeyOffset = PackageLayout.wrappedKeyOffset(saltLength)
        val header = headerWithoutWrappedKey.copyOf()
        wrappedKey.copyInto(header, wrappedKeyOffset)
        return header
    }

    /** Assembles the final package bytes = full header + payload ciphertext. */
    private fun assemble(fullHeader: ByteArray, payloadCiphertext: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fullHeader)
        out.write(payloadCiphertext)
        return out.toByteArray()
    }

    private fun writeUInt32(out: ByteArrayOutputStream, value: Long) {
        check(value in 0..0xFFFFFFFFL) { "uint32 out of range: $value" }
        out.write(((value ushr 24) and 0xff).toInt())
        out.write(((value ushr 16) and 0xff).toInt())
        out.write(((value ushr 8) and 0xff).toInt())
        out.write((value and 0xff).toInt())
    }

    private fun writeUInt16(out: ByteArrayOutputStream, value: Int) {
        check(value in 0..0xFFFF) { "uint16 out of range: $value" }
        out.write(((value ushr 8) and 0xff).toInt())
        out.write((value and 0xff).toInt())
    }
}
