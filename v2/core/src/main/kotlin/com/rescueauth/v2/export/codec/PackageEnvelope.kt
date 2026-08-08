package com.rescueauth.v2.export.codec

import kotlinx.serialization.json.Json

/**
 * Parsed v2 Portable Package envelope (Phase 3B).
 *
 * Produced by [PackageHeaderParser.parse] after every attacker-controlled
 * header field has been validated against [PackageFormat] limits. A decoded
 * [PackageEnvelope] is therefore guaranteed to have well-formed lengths and
 * KDF parameters inside the accepted decode range, so the expensive KDF may
 * safely run.
 *
 * ## Authenticated metadata / AAD
 *
 * [headerAadBytes] is the exact plaintext header region that the payload AEAD
 * authenticates: every envelope field from the magic up to (but excluding)
 * the payload ciphertext. [wrapAadBytes] is the shorter prefix (up to, but
 * excluding, the wrapped-key ciphertext) that the PackageKey-wrapping AEAD
 * authenticates. Because the header fields that determine how the ciphertext
 * is parsed / decrypted (format/crypto versions, KDF parameters, algorithm
 * ids, nonce lengths, sizes) are inside both AAD regions, an attacker cannot
 * tamper with them and keep a valid package (see PACKAGE_FORMAT.md §AAD and
 * THREAT_MODEL.md §AAD / version tampering).
 */
data class PackageEnvelope(
    val formatVersion: Int,
    val cryptoVersion: Int,
    val flags: Int,
    val kdfAlgorithm: Int,
    val kdfMemoryKiB: Int,
    val kdfIterations: Int,
    val kdfParallelism: Int,
    val kdfOutputLength: Int,
    val salt: ByteArray,
    val wrapAlgorithm: Int,
    val wrapNonce: ByteArray,
    val wrappedKey: ByteArray,
    val payloadAlgorithm: Int,
    val payloadNonce: ByteArray,
    val payloadCiphertext: ByteArray,
    /** Header bytes from magic up to the payload ciphertext (payload AEAD AAD). */
    val headerAadBytes: ByteArray,
    /** Header bytes from magic up to the wrapped-key ciphertext (wrap AEAD AAD). */
    val wrapAadBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is PackageEnvelope &&
            formatVersion == other.formatVersion &&
            cryptoVersion == other.cryptoVersion &&
            flags == other.flags &&
            kdfAlgorithm == other.kdfAlgorithm &&
            kdfMemoryKiB == other.kdfMemoryKiB &&
            kdfIterations == other.kdfIterations &&
            kdfParallelism == other.kdfParallelism &&
            kdfOutputLength == other.kdfOutputLength &&
            salt.contentEquals(other.salt) &&
            wrapAlgorithm == other.wrapAlgorithm &&
            wrapNonce.contentEquals(other.wrapNonce) &&
            wrappedKey.contentEquals(other.wrappedKey) &&
            payloadAlgorithm == other.payloadAlgorithm &&
            payloadNonce.contentEquals(other.payloadNonce) &&
            payloadCiphertext.contentEquals(other.payloadCiphertext) &&
            headerAadBytes.contentEquals(other.headerAadBytes) &&
            wrapAadBytes.contentEquals(other.wrapAadBytes)

    override fun hashCode(): Int {
        var h = formatVersion
        h = 31 * h + cryptoVersion
        h = 31 * h + flags
        h = 31 * h + kdfAlgorithm
        h = 31 * h + kdfMemoryKiB
        h = 31 * h + kdfIterations
        h = 31 * h + kdfParallelism
        h = 31 * h + kdfOutputLength
        h = 31 * h + salt.contentHashCode()
        h = 31 * h + wrapAlgorithm
        h = 31 * h + wrapNonce.contentHashCode()
        h = 31 * h + wrappedKey.contentHashCode()
        h = 31 * h + payloadAlgorithm
        h = 31 * h + payloadNonce.contentHashCode()
        h = 31 * h + payloadCiphertext.contentHashCode()
        h = 31 * h + headerAadBytes.contentHashCode()
        h = 31 * h + wrapAadBytes.contentHashCode()
        return h
    }
}

/**
 * Serializes / deserializes the logical payload JSON — the plaintext that is
 * AEAD-encrypted inside the package.
 *
 * - The JSON is deterministic for a given logical payload (kotlinx.serialization
 *   emits a stable field set for the same value), which supports
 *   validation/testing (e.g. "same logical payload exported twice with the
 *   same PIN still yields different ciphertext").
 * - `encodeDefaults = true` so structurally-present default fields are still
 *   emitted (the serialized form is self-describing and stable).
 * - `ignoreUnknownKeys = true` on decode keeps forward compatibility within
 *   the same `logicalSchemaVersion` (unknown JSON members are not fatal).
 * - The **outer** format/crypto versions and the **inner** logical schema
 *   version are deliberately distinct concepts (PACKAGE_FORMAT.md §Versioning).
 */
internal object PayloadJson {

    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = true
    }

    fun encode(payload: com.rescueauth.v2.export.VaultPackagePayload): ByteArray =
        json.encodeToString(
            com.rescueauth.v2.export.VaultPackagePayload.serializer(),
            payload,
        ).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): com.rescueauth.v2.export.VaultPackagePayload =
        json.decodeFromString(
            com.rescueauth.v2.export.VaultPackagePayload.serializer(),
            bytes.toString(Charsets.UTF_8),
        )
}
