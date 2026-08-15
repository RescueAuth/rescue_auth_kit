package com.rescueauth.v2.export

/**
 * Package capacity contract — the single source of truth that aligns the
 * **logical** validator with the **byte-level** codec limits
 * (PACKAGE_FORMAT.md §Capacity / ADR-0007 §Capacity).
 *
 * ## Why this object exists (merge-before-export consistency blocker)
 *
 * Phase 3A's logical validator allowed a single Android Signing Key
 * `keystoreBase64` up to [MAX_KEYSTORE_BASE64_LENGTH] (≈12 MiB of base64 text)
 * and a Vault may contain **multiple** Developer Entries. Without a total
 * budget a logical-valid FULL_VAULT could exceed the package byte cap and
 * therefore could **never** be fully exported — an undocumented,
 * validator-accepts-but-encode-impossible state.
 *
 * This object closes that gap:
 *
 * 1. It owns the whole-package / header / tag / serialized-payload byte caps
 *    that `PortablePackageCodec` enforces at encode time;
 * 2. It provides [estimateSerializedSize] — a provable **upper bound** of the
 *    kotlinx.serialization JSON bytes for a [VaultPackagePayload] — which
 *    [PackageValidator] enforces so that **any validator-accepted payload is
 *    guaranteed to be encodable** within the package limit;
 * 3. The codec keeps a post-serialization exact check as defense-in-depth, so
 *    an over-limit encode always fails **explicitly** with
 *    `PackageCodecException.PackageTooLarge` before any oversized allocation
 *    (no OOM).
 *
 * ## Capacity choice and Android memory rationale
 *
 * - **Package cap stays 16 MiB** (not raised). Encoding a payload consumes
 *   roughly: serialized JSON + ciphertext + Argon2id working set (default
 *   19 MiB) + transient base64 buffers. A 16 MiB cap keeps peak transient
 *   memory ≈ 50–60 MiB on the export path, which is acceptable on modern
 *   Android for a **manual, user-initiated** export; raising the cap to
 *   32 MiB would roughly double that peak and materially increase ANR/OOM
 *   risk on low-memory devices without a security benefit.
 * - **Logical limits are made consistent with that cap** (instead of raising
 *   the cap): the per-asset keystore cap is kept generous (a single real
 *   Android keystore, even a large one, still fits), while the **total**
 *   snapshot budget guarantees the whole payload fits. Combinations that
 *   cannot fit (e.g. multiple multi-MiB keystores) are rejected at
 *   validation/encode with a clear, documented error.
 */
object PackageCapacity {

    /** Whole portable package byte cap (format hard limit). */
    const val MAX_PACKAGE_SIZE = 16 * 1024 * 1024 // 16 MiB

    /** Header region cap (well above the current ~150-byte header). */
    const val MAX_HEADER_LENGTH = 4 * 1024

    /** XChaCha20-Poly1305 AEAD tag length. */
    const val XCHACHA20_TAG_BYTES = 16

    /** Wrapped-key region cap (defensive; the current value is 48). */
    const val MAX_WRAPPED_KEY_BYTES = 512

    /** Payload ciphertext cap: the package minus the header region. */
    const val MAX_PAYLOAD_CIPHERTEXT_SIZE = MAX_PACKAGE_SIZE - MAX_HEADER_LENGTH

    /** Serialized plaintext payload (JSON bytes) cap: ciphertext minus the AEAD tag. */
    const val MAX_SERIALIZED_PAYLOAD_SIZE = MAX_PAYLOAD_CIPHERTEXT_SIZE - XCHACHA20_TAG_BYTES

    // ------------------------------------------------------------------
    // Structural overhead allowances for estimateSerializedSize
    // ------------------------------------------------------------------
    // Deliberately generous per-record constants that comfortably cover the
    // JSON field names, separators, braces/commas and number fields emitted
    // by kotlinx.serialization (encodeDefaults=true). Because they are
    // upper-bound constants, the estimator is a provable ceiling of the real
    // serialized size (see PACKAGE_FORMAT.md §Capacity).
    const val PAYLOAD_WRAPPER_OVERHEAD = 512L // payload fields + source metadata wrapper
    const val SNAPSHOT_OVERHEAD = 256L        // "snapshot" wrapper + scope + section field names
    const val PER_ACCOUNT_OVERHEAD = 512L
    const val PER_TOTP_OVERHEAD = 256L
    const val PER_RECOVERY_SET_OVERHEAD = 256L
    const val PER_RECOVERY_CODE_OVERHEAD = 256L
    const val PER_DEVELOPER_ENTRY_OVERHEAD = 512L
    const val PER_KEY_VALUE_OVERHEAD = 128L

    /**
     * Provable upper bound of the JSON bytes produced by `PayloadJson.encode`
     * for [payload] (kotlinx.serialization, `encodeDefaults=true`).
     *
     * Every string field contributes [jsonStringBytes] (the exact worst-case
     * JSON encoding cost of that string), every record contributes a
     * structural overhead constant that covers its field names and
     * separators. Sum ≥ actual serialized size.
     */
    fun estimateSerializedSize(payload: VaultPackagePayload): Long {
        var total = PAYLOAD_WRAPPER_OVERHEAD
        total += jsonStringBytes(payload.packageId)
        total += jsonStringBytes(payload.createdAt)
        total += jsonStringBytes(payload.source.client)
        total += jsonStringBytes(payload.source.appVersion)
        payload.source.vaultInstanceId?.let { total += jsonStringBytes(it) }
        total += estimateSnapshot(payload.snapshot)
        return total
    }

    private fun estimateSnapshot(snapshot: VaultSnapshot): Long {
        var total = SNAPSHOT_OVERHEAD
        for (account in snapshot.accounts) {
            total += PER_ACCOUNT_OVERHEAD
            total += jsonStringBytes(account.stableId)
            total += jsonStringBytes(account.serviceName)
            total += jsonStringBytes(account.accountName)
            account.notes?.let { total += jsonStringBytes(it) }
            total += jsonStringBytes(account.createdAt)
            total += jsonStringBytes(account.updatedAt)
            for (totp in account.totpCredentials) {
                total += PER_TOTP_OVERHEAD
                total += jsonStringBytes(totp.stableId)
                total += jsonStringBytes(totp.secretBase32)
                total += jsonStringBytes(totp.algorithm)
                total += jsonStringBytes(totp.createdAt)
            }
            for (set in account.recoveryCodeSets) {
                total += PER_RECOVERY_SET_OVERHEAD
                total += jsonStringBytes(set.stableId)
                total += jsonStringBytes(set.title)
                total += jsonStringBytes(set.createdAt)
                for (code in set.codes) {
                    total += PER_RECOVERY_CODE_OVERHEAD
                    total += jsonStringBytes(code.stableId)
                    total += jsonStringBytes(code.value)
                    total += jsonStringBytes(code.status)
                    code.usedAt?.let { total += jsonStringBytes(it) }
                }
            }
        }
        for (entry in snapshot.developerEntries) {
            total += PER_DEVELOPER_ENTRY_OVERHEAD
            total += jsonStringBytes(entry.stableId)
            total += jsonStringBytes(entry.title)
            entry.notes?.let { total += jsonStringBytes(it) }
            total += jsonStringBytes(entry.createdAt)
            total += jsonStringBytes(entry.updatedAt)
            when (entry) {
                is VaultAndroidSigningKey -> {
                    total += jsonStringBytes(entry.projectName)
                    total += jsonStringBytes(entry.packageName)
                    total += jsonStringBytes(entry.keystoreFileName)
                    total += jsonStringBytes(entry.keystoreBase64)
                    total += jsonStringBytes(entry.storePassword)
                    total += jsonStringBytes(entry.keyAlias)
                    total += jsonStringBytes(entry.keyPassword)
                }
                is VaultApiCredential -> {
                    total += jsonStringBytes(entry.serviceName)
                    total += jsonStringBytes(entry.accountName)
                    total += jsonStringBytes(entry.apiKey)
                    total += jsonStringBytes(entry.apiSecret)
                }
                is VaultSshKey -> {
                    total += jsonStringBytes(entry.keyName)
                    total += jsonStringBytes(entry.publicKey)
                    total += jsonStringBytes(entry.privateKey)
                    total += jsonStringBytes(entry.passphrase)
                }
                is VaultEnvironmentVariableSet -> {
                    total += jsonStringBytes(entry.projectName)
                    for (kv in entry.variables) {
                        total += PER_KEY_VALUE_OVERHEAD
                        total += jsonStringBytes(kv.key)
                        total += jsonStringBytes(kv.value)
                    }
                }
                is VaultGenericSecret -> {
                    for (field in entry.fields) {
                        total += PER_KEY_VALUE_OVERHEAD
                        total += jsonStringBytes(field.key)
                        total += jsonStringBytes(field.value)
                    }
                }
            }
        }
        return total
    }

    /**
     * Exact worst-case JSON encoding cost of one string field: the surrounding
     * quotes plus, per character, the maximal UTF-8/escape length the JSON
     * encoder can emit for that character. This is an **upper bound**, never an
     * under-estimate, so the total estimate is a provable ceiling.
     */
    fun jsonStringBytes(value: String): Long {
        var total = 2L // surrounding quotes
        for (ch in value) {
            total += when {
                ch == '"' || ch == '\\' -> 2 // \" or \\
                ch.code < 0x20 -> 6 // \uXXXX
                ch.code < 0x80 -> 1
                else -> 4 // up to 4 UTF-8 bytes (kotlinx emits non-ASCII raw)
            }
        }
        return total
    }
}
