package com.rescueauth.v2.legacy

/**
 * High-level read-only importer for legacy `.rakvault` files.
 *
 * Pipeline (see docs/LEGACY_IMPORT.md §7.2):
 *   1. bound the input size
 *   2. parse + validate the envelope header (KDF caps checked BEFORE Argon2)
 *   3. Argon2id + XChaCha20-Poly1305 decrypt (background thread, caller's job)
 *   4. parse the legacy payload into a uniform [LegacyImportBundle]
 *
 * This class never writes legacy files, never logs secret material, and never
 * touches the v2 database — it only produces an import bundle for preview.
 */
class LegacyRakVaultImporter(
    private val maxInputBytes: Int = DEFAULT_MAX_INPUT_BYTES,
) {
    class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun import(encryptedBytes: ByteArray, password: String): LegacyImportBundle {
        if (encryptedBytes.isEmpty()) throw ImportException("File is empty")
        if (encryptedBytes.size > maxInputBytes) {
            throw ImportException("File too large: ${encryptedBytes.size} bytes (max $maxInputBytes)")
        }

        val jsonText = String(encryptedBytes, Charsets.UTF_8)
        val envelope = try {
            LegacyKdfValidator.parse(jsonText)
        } catch (e: LegacyKdfValidator.ValidationException) {
            throw ImportException(e.message ?: "Invalid envelope", e)
        }
        try {
            LegacyKdfValidator.validate(envelope)
        } catch (e: LegacyKdfValidator.ValidationException) {
            throw ImportException(e.message ?: "Envelope validation failed", e)
        }

        val key = try {
            LegacyVaultDecryptor.deriveArgon2idKey(
                password = password,
                params = envelope.kdf,
                salt = LegacyKdfValidator.decodeB64(envelope.kdf.saltB64),
            )
        } catch (e: Exception) {
            throw ImportException("Argon2id derivation failed", e)
        }

        val ciphertext = try {
            LegacyKdfValidator.decodeB64(envelope.ciphertextB64)
        } catch (e: LegacyKdfValidator.ValidationException) {
            throw ImportException(e.message ?: "Invalid ciphertext", e)
        }
        val mac = try {
            LegacyKdfValidator.decodeB64(envelope.macB64)
        } catch (e: LegacyKdfValidator.ValidationException) {
            throw ImportException(e.message ?: "Invalid mac", e)
        }
        if (mac.size != 16) {
            throw ImportException("Invalid MAC length: ${mac.size} (expected 16)")
        }
        val nonce = LegacyKdfValidator.decodeB64(envelope.nonceB64)

        // Legacy format stores ciphertext and MAC as separate fields, but BC's
        // ChaCha20Poly1305 AEAD expects ct||tag concatenated.
        val encryptedWithTag = ciphertext + mac

        val plaintext = try {
            LegacyVaultDecryptor.decrypt(encryptedWithTag, key, nonce)
        } catch (e: LegacyVaultDecryptor.DecryptException) {
            throw ImportException(e.message ?: "Decryption failed", e)
        } finally {
            // Zero the derived key as soon as it is no longer needed.
            key.fill(0)
            encryptedWithTag.fill(0)
        }

        if (plaintext.isEmpty()) throw ImportException("Decrypted payload is empty")
        if (plaintext.size > MAX_PAYLOAD_BYTES) {
            throw ImportException("Decrypted payload too large: ${plaintext.size} bytes")
        }

        return try {
            LegacyPayloadParser.parse(String(plaintext, Charsets.UTF_8))
        } catch (e: LegacyPayloadParser.ParseException) {
            throw ImportException(e.message ?: "Payload parse failed", e)
        } finally {
            plaintext.fill(0)
        }
    }

    companion object {
        //
        // Legacy defensive limits — INDEPENDENT of the Native `.rakpkg`
        // 16 MiB package contract (ADR-0010 §6 / PHASE5A_REPORT §14).
        //
        // frozen v1.2.0 has NO file size cap: `vault_repository.dart`
        // `open()`/`importBytes()` read the entire file via `readAsBytes()`
        // and `VaultFile.decode()` runs `jsonDecode` on the whole string.
        // The previous 16 MiB input cap was copied from the Phase 3B Native
        // package contract, which does NOT apply to legacy `.rakvault`.
        //
        // Historical v1 vaults can legitimately carry a Developer Vault with
        // Android signing keystore binaries and multiple Developer entries;
        // a base64url JSON envelope with several MiB of keystore material is
        // realistic. 64 MiB input gives ~4x headroom over the largest
        // plausible real v1 vault while still bounding the whole-file
        // read + Argon2id working set on the import path (defensive, not a
        // format contract).
        const val DEFAULT_MAX_INPUT_BYTES = 64 * 1024 * 1024 // 64 MiB

        // Post-decrypt backstop. Because the envelope stores the ciphertext
        // as base64url (4/3 expansion) plus a 16-byte AEAD tag, a 64 MiB
        // input cannot yield more than ~48 MiB of plaintext, so this cap is
        // effectively unreachable for a valid envelope — it is kept as a
        // clear, separately-documented legacy defensive bound in case a
        // future legacy schema ever encodes the payload more densely.
        const val MAX_PAYLOAD_BYTES = 64 * 1024 * 1024 // 64 MiB (post-decrypt cap)
    }
}
