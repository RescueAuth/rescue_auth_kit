package com.rescueauth.v2.vaultkey

import com.rescueauth.v2.export.codec.PackageCodecException
import com.rescueauth.v2.export.codec.PackageCrypto
import com.rescueauth.v2.export.codec.PackageRuntimePolicy
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-JVM cryptographic envelope codec for wrapping the 32-byte random
 * `VaultKey` under a user-chosen Backup PIN (ADR-0015).
 *
 * ## Security & format properties
 * - Reuses the validated Bouncy Castle 1.85 **Argon2id v13** + **XChaCha20-Poly1305**
 *   primitives from [PackageCrypto] (no new crypto dependencies).
 * - Fixed 114-byte binary envelope (`RAVKPIN1`) whose entire 66-byte header is
 *   authenticated as AEAD AAD so KDF parameters, version, salt, and nonce
 *   cannot be tampered with.
 * - Enforces mobile runtime KDF bounds ([PackageRuntimePolicy]) *before*
 *   allocating Argon2id memory.
 * - Best-effort zeroizes all transient secret buffers (`pinBytes`, `kek`, and
 *   verification-only unwrapped copies).
 */
object BackupPinCodec {

    const val VAULT_KEY_BYTES: Int = 32
    const val FORMAT_VERSION: Int = 1
    const val KDF_ARGON2ID_V13: Int = 1
    const val AEAD_XCHACHA20_POLY1305: Int = 1

    const val MIN_PIN_LENGTH: Int = 6
    const val MAX_PIN_LENGTH: Int = 128

    const val SALT_BYTES: Int = 16
    const val NONCE_BYTES: Int = 24
    const val KEK_BYTES: Int = 32
    const val AEAD_TAG_BYTES: Int = 16

    /** 64 MiB / 3 iterations / p = 1 (196,608 cost units — within PackageRuntimePolicy). */
    const val DEFAULT_MEMORY_KIB: Int = 64 * 1024
    const val DEFAULT_ITERATIONS: Int = 3
    const val DEFAULT_PARALLELISM: Int = 1

    const val MIN_MEMORY_KIB: Int = 8 * 1024
    const val MAX_MEMORY_KIB: Int = PackageRuntimePolicy.MAX_MEMORY_KIB
    const val MIN_ITERATIONS: Int = 1
    const val MAX_ITERATIONS: Int = PackageRuntimePolicy.MAX_ITERATIONS
    const val MIN_PARALLELISM: Int = 1
    const val MAX_PARALLELISM: Int = 4
    const val MAX_COST_UNITS: Long = PackageRuntimePolicy.MAX_COST_UNITS.toLong()

    private val MAGIC: ByteArray = "RAVKPIN1".encodeToByteArray()
    private const val HEADER_BYTES: Int = 8 + 4 + 12 + 1 + SALT_BYTES + 1 + NONCE_BYTES // 66
    private const val CIPHERTEXT_AND_TAG_BYTES: Int = VAULT_KEY_BYTES + AEAD_TAG_BYTES // 48
    const val ENVELOPE_BYTES: Int = HEADER_BYTES + CIPHERTEXT_AND_TAG_BYTES // 114

    data class KdfParams(
        val memoryKiB: Int = DEFAULT_MEMORY_KIB,
        val iterations: Int = DEFAULT_ITERATIONS,
        val parallelism: Int = DEFAULT_PARALLELISM,
    ) {
        init {
            validateKdfParams(memoryKiB, iterations, parallelism)
        }
    }

    enum class PinIssue {
        EMPTY,
        NON_DIGIT,
        TOO_SHORT,
        TOO_LONG,
    }

    sealed class BackupPinException(message: String, cause: Throwable? = null) : Exception(message, cause)

    class InvalidPinException(val issue: PinIssue) :
        BackupPinException("Invalid backup PIN: $issue")

    class CorruptEnvelopeException(message: String, cause: Throwable? = null) :
        BackupPinException(message, cause)

    class AuthenticationFailedException :
        BackupPinException("Backup PIN verification failed")

    /** Validates a newly chosen Backup PIN (6..128 ASCII digits). */
    fun validateNewPin(pin: String): PinIssue? {
        if (pin.isEmpty()) return PinIssue.EMPTY
        for (ch in pin) {
            if (ch < '0' || ch > '9') return PinIssue.NON_DIGIT
        }
        if (pin.length < MIN_PIN_LENGTH) return PinIssue.TOO_SHORT
        if (pin.length > MAX_PIN_LENGTH) return PinIssue.TOO_LONG
        return null
    }

    /**
     * Wraps a 32-byte [vaultKey] under [pin] and returns the 114-byte binary envelope.
     * Caller remains responsible for zeroing [vaultKey] when finished with it.
     */
    fun wrap(
        vaultKey: ByteArray,
        pin: String,
        kdfParams: KdfParams = KdfParams(),
    ): ByteArray {
        require(vaultKey.size == VAULT_KEY_BYTES) {
            "VaultKey must be $VAULT_KEY_BYTES bytes, got ${vaultKey.size}"
        }
        validateNewPin(pin)?.let { throw InvalidPinException(it) }

        val salt = PackageCrypto.newSalt(SALT_BYTES)
        val nonce = PackageCrypto.newNonce()
        val header = encodeHeader(kdfParams, salt, nonce)
        val pinBytes = pin.encodeToByteArray()
        var kek: ByteArray? = null
        try {
            kek = PackageCrypto.argon2id(
                password = pinBytes,
                salt = salt,
                memoryKiB = kdfParams.memoryKiB,
                iterations = kdfParams.iterations,
                parallelism = kdfParams.parallelism,
                outputLength = KEK_BYTES,
            )
            val ciphertextAndTag = PackageCrypto.aeadEncrypt(
                key = kek,
                nonce = nonce,
                aad = header,
                plaintext = vaultKey,
            )
            check(ciphertextAndTag.size == CIPHERTEXT_AND_TAG_BYTES)
            return ByteArray(ENVELOPE_BYTES).also { out ->
                System.arraycopy(header, 0, out, 0, HEADER_BYTES)
                System.arraycopy(ciphertextAndTag, 0, out, HEADER_BYTES, CIPHERTEXT_AND_TAG_BYTES)
            }
        } finally {
            PackageCrypto.zeroize(pinBytes)
            kek?.let(PackageCrypto::zeroize)
        }
    }

    /**
     * Unwraps [envelope] with [pin] and returns a freshly allocated 32-byte `VaultKey`.
     *
     * @throws CorruptEnvelopeException if [envelope] is malformed or has unsafe KDF parameters.
     * @throws AuthenticationFailedException if [pin] is wrong or the AEAD tag fails.
     */
    fun unwrap(envelope: ByteArray, pin: String): ByteArray {
        val parsed = parseEnvelope(envelope)
        if (pin.isEmpty()) throw AuthenticationFailedException()
        for (ch in pin) {
            if (ch < '0' || ch > '9') throw AuthenticationFailedException()
        }
        if (pin.length > MAX_PIN_LENGTH) throw AuthenticationFailedException()

        val pinBytes = pin.encodeToByteArray()
        var kek: ByteArray? = null
        try {
            kek = PackageCrypto.argon2id(
                password = pinBytes,
                salt = parsed.salt,
                memoryKiB = parsed.kdfParams.memoryKiB,
                iterations = parsed.kdfParams.iterations,
                parallelism = parsed.kdfParams.parallelism,
                outputLength = KEK_BYTES,
            )
            val plaintext = try {
                PackageCrypto.aeadDecrypt(
                    key = kek,
                    nonce = parsed.nonce,
                    aad = parsed.header,
                    ciphertextAndTag = parsed.ciphertextAndTag,
                )
            } catch (_: PackageCodecException.AuthenticationFailed) {
                throw AuthenticationFailedException()
            }
            if (plaintext.size != VAULT_KEY_BYTES) {
                PackageCrypto.zeroize(plaintext)
                throw CorruptEnvelopeException("Unexpected unwrapped VaultKey size: ${plaintext.size}")
            }
            return plaintext
        } finally {
            PackageCrypto.zeroize(pinBytes)
            kek?.let(PackageCrypto::zeroize)
        }
    }

    /**
     * Verifies whether [pin] unlocks [envelope] and immediately zeroes the
     * unwrapped key material before returning.
     *
     * @throws CorruptEnvelopeException if [envelope] is structurally invalid.
     */
    fun verifyPin(envelope: ByteArray, pin: String): Boolean {
        var unwrapped: ByteArray? = null
        return try {
            unwrapped = unwrap(envelope, pin)
            true
        } catch (_: AuthenticationFailedException) {
            false
        } finally {
            unwrapped?.let(PackageCrypto::zeroize)
        }
    }

    private fun encodeHeader(kdf: KdfParams, salt: ByteArray, nonce: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC)
        buf.put(FORMAT_VERSION.toByte())
        buf.put(KDF_ARGON2ID_V13.toByte())
        buf.put(AEAD_XCHACHA20_POLY1305.toByte())
        buf.put(0.toByte())
        buf.putInt(kdf.memoryKiB)
        buf.putInt(kdf.iterations)
        buf.putInt(kdf.parallelism)
        buf.put(salt.size.toByte())
        buf.put(salt)
        buf.put(nonce.size.toByte())
        buf.put(nonce)
        return buf.array()
    }

    private class ParsedEnvelope(
        val header: ByteArray,
        val kdfParams: KdfParams,
        val salt: ByteArray,
        val nonce: ByteArray,
        val ciphertextAndTag: ByteArray,
    )

    private fun parseEnvelope(envelope: ByteArray): ParsedEnvelope {
        if (envelope.size != ENVELOPE_BYTES) {
            throw CorruptEnvelopeException(
                "Invalid backup PIN envelope length: ${envelope.size} (expected $ENVELOPE_BYTES)",
            )
        }
        for (i in MAGIC.indices) {
            if (envelope[i] != MAGIC[i]) {
                throw CorruptEnvelopeException("Invalid backup PIN envelope magic")
            }
        }
        val buf = ByteBuffer.wrap(envelope).order(ByteOrder.BIG_ENDIAN)
        buf.position(MAGIC.size)
        val version = buf.get().toInt() and 0xFF
        if (version != FORMAT_VERSION) {
            throw CorruptEnvelopeException("Unsupported backup PIN envelope version: $version")
        }
        val kdfAlg = buf.get().toInt() and 0xFF
        if (kdfAlg != KDF_ARGON2ID_V13) {
            throw CorruptEnvelopeException("Unsupported backup PIN KDF algorithm: $kdfAlg")
        }
        val aeadAlg = buf.get().toInt() and 0xFF
        if (aeadAlg != AEAD_XCHACHA20_POLY1305) {
            throw CorruptEnvelopeException("Unsupported backup PIN AEAD algorithm: $aeadAlg")
        }
        val reserved = buf.get().toInt() and 0xFF
        if (reserved != 0) {
            throw CorruptEnvelopeException("Non-zero reserved byte in backup PIN header")
        }
        val memoryKiB = buf.int
        val iterations = buf.int
        val parallelism = buf.int
        val kdfParams = try {
            KdfParams(memoryKiB = memoryKiB, iterations = iterations, parallelism = parallelism)
        } catch (e: IllegalArgumentException) {
            throw CorruptEnvelopeException("Unsafe or invalid backup PIN KDF parameters: ${e.message}", e)
        }
        val saltLen = buf.get().toInt() and 0xFF
        if (saltLen != SALT_BYTES) {
            throw CorruptEnvelopeException("Invalid salt length: $saltLen")
        }
        val salt = ByteArray(SALT_BYTES).also { buf.get(it) }
        val nonceLen = buf.get().toInt() and 0xFF
        if (nonceLen != NONCE_BYTES) {
            throw CorruptEnvelopeException("Invalid nonce length: $nonceLen")
        }
        val nonce = ByteArray(NONCE_BYTES).also { buf.get(it) }
        val header = envelope.copyOfRange(0, HEADER_BYTES)
        val ciphertextAndTag = envelope.copyOfRange(HEADER_BYTES, ENVELOPE_BYTES)
        return ParsedEnvelope(header, kdfParams, salt, nonce, ciphertextAndTag)
    }

    private fun validateKdfParams(memoryKiB: Int, iterations: Int, parallelism: Int) {
        require(memoryKiB in MIN_MEMORY_KIB..MAX_MEMORY_KIB) {
            "memoryKiB $memoryKiB out of allowed range [$MIN_MEMORY_KIB, $MAX_MEMORY_KIB]"
        }
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) {
            "iterations $iterations out of allowed range [$MIN_ITERATIONS, $MAX_ITERATIONS]"
        }
        require(parallelism in MIN_PARALLELISM..MAX_PARALLELISM) {
            "parallelism $parallelism out of allowed range [$MIN_PARALLELISM, $MAX_PARALLELISM]"
        }
        val cost = memoryKiB.toLong() * iterations.toLong()
        require(cost <= MAX_COST_UNITS) {
            "KDF cost $cost exceeds max runtime budget $MAX_COST_UNITS"
        }
    }
}
