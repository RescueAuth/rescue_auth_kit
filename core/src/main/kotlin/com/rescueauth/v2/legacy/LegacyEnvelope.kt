package com.rescueauth.v2.legacy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Legacy `.rakvault` envelope (magic `RescueAuthKitVault`, version 1).
 *
 * Mirrors `lib/core/crypto/vault_crypto.dart` at tag `v1.2.0`.
 * Read-only model: this module never writes legacy files.
 */
@Serializable
data class LegacyKdfParams(
    val name: String,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    val hashLengthBytes: Int,
    @SerialName("saltB64") val saltB64: String,
)

@Serializable
data class LegacyEnvelope(
    val magic: String,
    val version: Int,
    val kdf: LegacyKdfParams,
    val cipher: String,
    @SerialName("nonceB64") val nonceB64: String,
    @SerialName("macB64") val macB64: String,
    @SerialName("ciphertextB64") val ciphertextB64: String,
)

/**
 * Validates the envelope header BEFORE any expensive KDF work.
 *
 * Security: the legacy KDF is Argon2id with attacker-controlled parameters.
 * A malicious/truncated file could claim an enormous memory cost and abort the
 * process (observed: an 8 GiB claim OOM-aborts). Limits below are the
 * contract in docs/LEGACY_IMPORT.md §7.
 */
object LegacyKdfValidator {

    const val MAGIC = "RescueAuthKitVault"
    const val VERSION = 1
    const val CIPHER = "xchacha20poly1305"
    const val KDF_NAME = "argon2id"

    // Safety caps (docs/LEGACY_IMPORT.md §7).
    const val MAX_MEMORY_KIB = 262_144 // 256 MiB
    const val MAX_ITERATIONS = 16
    const val MAX_PARALLELISM = 8
    const val MIN_HASH_LENGTH = 16
    const val MAX_HASH_LENGTH = 64
    const val MIN_SALT_BYTES = 8
    const val MAX_SALT_BYTES = 64
    const val NONCE_BYTES = 24 // XChaCha20

    class ValidationException(message: String) : Exception(message)

    fun validate(envelope: LegacyEnvelope) {
        if (envelope.magic != MAGIC) {
            throw ValidationException("Not a RescueAuthKit vault file (magic mismatch)")
        }
        if (envelope.version != VERSION) {
            throw ValidationException("Unsupported legacy vault version: ${envelope.version}")
        }
        if (envelope.cipher != CIPHER) {
            throw ValidationException("Unsupported legacy cipher: ${envelope.cipher}")
        }
        val kdf = envelope.kdf
        if (kdf.name != KDF_NAME) {
            throw ValidationException("Unsupported legacy KDF: ${kdf.name}")
        }
        if (kdf.memoryKiB <= 0 || kdf.memoryKiB > MAX_MEMORY_KIB) {
            throw ValidationException(
                "KDF memoryKiB out of safe range: ${kdf.memoryKiB} (max $MAX_MEMORY_KIB)"
            )
        }
        if (kdf.iterations < 1 || kdf.iterations > MAX_ITERATIONS) {
            throw ValidationException("KDF iterations out of safe range: ${kdf.iterations}")
        }
        if (kdf.parallelism < 1 || kdf.parallelism > MAX_PARALLELISM) {
            throw ValidationException("KDF parallelism out of safe range: ${kdf.parallelism}")
        }
        if (kdf.hashLengthBytes !in MIN_HASH_LENGTH..MAX_HASH_LENGTH) {
            throw ValidationException(
                "KDF hashLengthBytes out of safe range: ${kdf.hashLengthBytes}"
            )
        }
        val salt = decodeB64(kdf.saltB64)
        if (salt.size !in MIN_SALT_BYTES..MAX_SALT_BYTES) {
            throw ValidationException("KDF salt length out of safe range: ${salt.size}")
        }
        val nonce = decodeB64(envelope.nonceB64)
        if (nonce.size != NONCE_BYTES) {
            throw ValidationException("Nonce must be $NONCE_BYTES bytes, got ${nonce.size}")
        }
        // MAC and ciphertext are validated by the AEAD layer (and by size caps
        // applied when reading the file).
    }

    fun decodeB64(s: String): ByteArray {
        return try {
            java.util.Base64.getUrlDecoder().decode(s)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Invalid base64url field")
        }
    }

    /** Parse JSON envelope; callers must bound the input size before parsing. */
    fun parse(jsonText: String): LegacyEnvelope {
        return try {
            Json { ignoreUnknownKeys = true }.decodeFromString(LegacyEnvelope.serializer(), jsonText)
        } catch (e: Exception) {
            throw ValidationException("Malformed envelope JSON: ${e.message}")
        }
    }
}
