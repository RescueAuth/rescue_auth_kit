package com.rescueauth.v2.export.codec

import com.rescueauth.v2.export.PackageCapacity

/**
 * v2 Portable Package wire format — constants, hard limits, error taxonomy.
 *
 * This is the **outer envelope** contract of the v2 Export Package (Phase 3B).
 * It is intentionally independent from the logical payload
 * ([com.rescueauth.v2.export.VaultPackagePayload]) and from the legacy
 * `.rakvault` envelope — the two crypto stacks are fully isolated
 * (ROADMAP §9 / PACKAGE_FORMAT.md §Legacy).
 *
 * ## Byte layout (all multi-byte integers big-endian, no alignment padding)
 *
 * ```
 * offset   size  field
 * 0        8     magic        = "RAKVPKG2"
 * 8        1     formatVersion = 1
 * 9        1     cryptoVersion = 1
 * 10       1     flags         = 0
 * 11       4     headerLength  (uint32) — bytes of the header region that
 *                        follows the 4-byte headerLength field itself; the
 *                        header region ends exactly where the payload
 *                        ciphertext begins
 * 15       1     kdfAlgorithm  (1 = ARGON2ID)
 * 16       4     kdfMemoryKiB  (uint32)
 * 20       4     kdfIterations (uint32)
 * 24       1     kdfParallelism
 * 25       1     kdfOutputLength (bytes)
 * 26       1     kdfSaltLength   (bytes)
 * 27       n     kdfSalt
 * next     1     wrappingAlgorithm (1 = XCHACHA20_POLY1305)
 * next     1     wrappingNonceLength (24)
 * next     24    wrappingNonce
 * next     2     wrappedKeyLength (uint16 = 48 for XChaCha20-Poly1305)
 * next     48    wrappedKey (ciphertext || tag of the 32-byte PackageKey)
 * next     1     payloadAlgorithm (1 = XCHACHA20_POLY1305)
 * next     1     payloadNonceLength (24)
 * next     24    payloadNonce
 * next     4     payloadCiphertextLength (uint32)
 * next     n     payloadCiphertext (ciphertext || tag of the serialized payload)
 * ```
 *
 * `headerLength = 70 + kdfSaltLength + wrappedKeyLength` for the current
 * layout (see [PackageLayout.headerLengthValue]).
 *
 * ## Authenticated metadata (AAD) — see PortablePackageCodec
 *
 * - **Wrap AAD** (authenticated by the PackageKey-wrapping AEAD) = the whole
 *   header prefix up to but **excluding** the wrapped-key ciphertext region.
 *   This binds magic, format/crypto versions, flags, every KDF parameter and
 *   the wrapping metadata to the wrapped PackageKey, so an attacker cannot
 *   silently re-interpret KDF/version metadata.
 * - **Payload AAD** (authenticated by the payload AEAD) = the **entire**
 *   header prefix up to but excluding the payload ciphertext region. This
 *   binds every envelope field that affects how the ciphertext is parsed or
 *   decrypted to the plaintext.
 *
 * ## Header is untrusted input
 *
 * The decoder validates **every** attacker-controlled header field
 * (magic / versions / lengths / KDF parameters) against the hard limits below
 * and throws [PackageCodecException.InvalidKdfParameters] /
 * [PackageCodecException.MalformedPackage] **before** running Argon2id or
 * allocating large buffers. A hostile package can never force an unbounded
 * memory/time allocation (see THREAT_MODEL.md §Malicious header).
 */
object PackageFormat {

    /** 8-byte magic. */
    const val MAGIC = "RAKVPKG2"

    /** Byte length of [MAGIC]. */
    const val MAGIC_BYTES = 8

    /** Current envelope layout version. */
    const val CURRENT_FORMAT_VERSION = 1

    /** Current crypto stack version (algorithm set + AAD layout). */
    const val CURRENT_CRYPTO_VERSION = 1

    /** Reserved header flags; currently must be 0. */
    const val FLAGS_NONE = 0

    // Algorithm ids (cryptoVersion-scoped).
    const val KDF_ALGORITHM_ARGON2ID = 1
    const val WRAP_ALGORITHM_XCHACHA20_POLY1305 = 1
    const val PAYLOAD_ALGORITHM_XCHACHA20_POLY1305 = 1

    // AEAD constants (XChaCha20-Poly1305).
    const val XCHACHA20_NONCE_BYTES = 24
    const val XCHACHA20_TAG_BYTES = 16

    /** PackageKey is a 256-bit cryptographically random key. */
    const val PACKAGE_KEY_BYTES = 32

    /** Wrapped PackageKey = ciphertext(32) + tag(16). */
    const val WRAPPED_PACKAGE_KEY_BYTES = PACKAGE_KEY_BYTES + XCHACHA20_TAG_BYTES

    /** Fixed header prefix before the variable-length fields. */
    const val HEADER_FIXED_BYTES = 15

    // ------------------------------------------------------------------
    // KDF policy — DEFAULT PARAMETERS (what encode uses today)
    // ------------------------------------------------------------------
    // Locked to the Phase 1 verified Argon2id parameters (19 MiB / 2 / p1 /
    // 32B) — the same cost class validated against the legacy default and the
    // OWASP interactive-login recommendation. These are the encode-time
    // defaults; decode reads the parameters from each package (so future
    // exports can use stronger KDF params) but only accepts the safe range
    // below.
    const val DEFAULT_KDF_ALGORITHM = KDF_ALGORITHM_ARGON2ID
    const val DEFAULT_MEMORY_KIB = 19456          // 19 MiB
    const val DEFAULT_ITERATIONS = 2
    const val DEFAULT_PARALLELISM = 1
    const val DEFAULT_OUTPUT_LENGTH = PACKAGE_KEY_BYTES
    const val DEFAULT_SALT_BYTES = 16

    // ------------------------------------------------------------------
    // KDF policy — ACCEPTABLE DECODE RANGE
    // ------------------------------------------------------------------
    // A decoder accepts any package whose KDF parameters fall in this range.
    // This keeps forward compatibility (a future stronger-but-reasonable
    // export still decrypts) while guaranteeing a bounded worst-case cost.
    const val MIN_MEMORY_KIB = 64                 // >= 8 * max parallelism (8)
    const val MAX_MEMORY_KIB = 262_144            // 256 MiB (same cap as legacy import)
    const val MIN_ITERATIONS = 1
    const val MAX_ITERATIONS = 16
    const val MIN_PARALLELISM = 1
    const val MAX_PARALLELISM = 8
    const val MIN_SALT_BYTES = 8
    const val MAX_SALT_BYTES = 64

    // ------------------------------------------------------------------
    // KDF output length — cryptoVersion-scoped (cryptoVersion=1)
    // ------------------------------------------------------------------
    // For cryptoVersion=1 the Argon2id output IS the XChaCha20-Poly1305
    // wrapping KEK directly (no extra KDF-output → 32-byte-KEK derivation
    // step exists). XChaCha20-Poly1305 requires a 32-byte key, therefore a
    // cryptoVersion=1 package MUST declare kdfOutputLength == 32.
    //
    // The historical loose 16..64 accepted range is deliberately removed:
    // the codec cannot consume any other output length — accepting one would
    // either create an unusable KEK or silently truncate/pad it, which is an
    // undefined and unsafe state. If a future cryptoVersion adds a key
    // derivation step, it must define its own output-length rule; the
    // version-scoped validation below must be extended with it.
    const val KDF_OUTPUT_LENGTH_FOR_CRYPTO_V1 = PACKAGE_KEY_BYTES

    // ------------------------------------------------------------------
    // Package-level hard limits (format-level, overflow-safe parsing)
    // ------------------------------------------------------------------
    // The byte caps are defined ONCE in [PackageCapacity] (shared with the
    // logical validator via [PackageCapacity.MAX_SERIALIZED_PAYLOAD_SIZE]) so
    // the logical and package contracts cannot drift apart
    // (PACKAGE_FORMAT.md §Capacity).

    /** Total package byte size cap. */
    const val MAX_PACKAGE_SIZE = PackageCapacity.MAX_PACKAGE_SIZE

    /** Header region cap (well above the current ~150-byte header). */
    const val MAX_HEADER_LENGTH = PackageCapacity.MAX_HEADER_LENGTH

    /** Payload ciphertext size cap (package minus the header region). */
    const val MAX_PAYLOAD_CIPHERTEXT_SIZE = PackageCapacity.MAX_PAYLOAD_CIPHERTEXT_SIZE

    /** Serialized plaintext payload cap (ciphertext minus the AEAD tag). */
    const val MAX_SERIALIZED_PAYLOAD_SIZE = PackageCapacity.MAX_SERIALIZED_PAYLOAD_SIZE

    /** Wrapped-key region cap (defensive; the current value is 48). */
    const val MAX_WRAPPED_KEY_BYTES = PackageCapacity.MAX_WRAPPED_KEY_BYTES

    // ------------------------------------------------------------------
    // RUNTIME DECODE RESOURCE POLICY (Android decoder budget)
    // ------------------------------------------------------------------
    // This is deliberately SEPARATE from the FORMAT HARD LIMITS above and from
    // the ACCEPTABLE DECODE RANGE. A package may be structurally valid and
    // inside the format hard limits, yet still demand an Argon2id cost that is
    // unsafe on a mobile device. The production/default decoder therefore
    // refuses parameters above this runtime budget BEFORE running Argon2id
    // (PortablePackageCodec.decode → [PackageRuntimePolicy.checkDecodeBudget]).
    //
    // Budget is measured in Argon2id memory-time cost units: costUnits =
    // memoryKiB * iterations. The default encode parameters
    // (19456 KiB × 2 iters ≈ 38 912 units) are always accepted; the budget is
    // set at 4× the default to leave headroom for stronger future exports
    // while keeping the worst-case single Argon2id allocation ≈ 128 MiB on a
    // mobile process.
    const val RUNTIME_MAX_MEMORY_KIB = 128 * 1024       // 128 MiB working set
    const val RUNTIME_MAX_ITERATIONS = 8
    const val RUNTIME_MAX_COST_UNITS = 512 * 1024        // memoryKiB × iterations
    const val RUNTIME_DEFAULT_COST_UNITS =
        DEFAULT_MEMORY_KIB.toLong() * DEFAULT_ITERATIONS // ≈ 38 912 (default encode)
}

/**
 * Byte offsets for the v2 envelope (see [PackageFormat] for the layout).
 *
 * Offsets that depend on variable-length fields are functions of
 * `saltLength` / `wrappedKeyLength`.
 */
object PackageLayout {

    const val OFF_MAGIC = 0
    const val OFF_FORMAT_VERSION = 8
    const val OFF_CRYPTO_VERSION = 9
    const val OFF_FLAGS = 10
    const val OFF_HEADER_LENGTH = 11
    const val OFF_KDF_ALGORITHM = 15
    const val OFF_KDF_MEMORY_KIB = 16
    const val OFF_KDF_ITERATIONS = 20
    const val OFF_KDF_PARALLELISM = 24
    const val OFF_KDF_OUTPUT_LENGTH = 25
    const val OFF_KDF_SALT_LENGTH = 26
    const val OFF_KDF_SALT = 27

    fun wrapAlgorithmOffset(saltLength: Int): Int = 27 + saltLength
    fun wrapNonceLengthOffset(saltLength: Int): Int = wrapAlgorithmOffset(saltLength) + 1
    fun wrapNonceOffset(saltLength: Int): Int = wrapNonceLengthOffset(saltLength) + 1
    fun wrappedKeyLengthOffset(saltLength: Int): Int =
        wrapNonceOffset(saltLength) + PackageFormat.XCHACHA20_NONCE_BYTES
    fun wrappedKeyOffset(saltLength: Int): Int = wrappedKeyLengthOffset(saltLength) + 2

    fun payloadAlgorithmOffset(saltLength: Int, wrappedKeyLength: Int): Int =
        wrappedKeyOffset(saltLength) + wrappedKeyLength
    fun payloadNonceLengthOffset(saltLength: Int, wrappedKeyLength: Int): Int =
        payloadAlgorithmOffset(saltLength, wrappedKeyLength) + 1
    fun payloadNonceOffset(saltLength: Int, wrappedKeyLength: Int): Int =
        payloadNonceLengthOffset(saltLength, wrappedKeyLength) + 1
    fun payloadCiphertextLengthOffset(saltLength: Int, wrappedKeyLength: Int): Int =
        payloadNonceOffset(saltLength, wrappedKeyLength) + PackageFormat.XCHACHA20_NONCE_BYTES

    /** Byte index where the payload ciphertext begins (== the payload AAD end). */
    fun headerEnd(saltLength: Int, wrappedKeyLength: Int): Int =
        payloadCiphertextLengthOffset(saltLength, wrappedKeyLength) + 4

    /** The `headerLength` field value for a given salt/wrapped-key length. */
    fun headerLengthValue(saltLength: Int, wrappedKeyLength: Int): Int =
        headerEnd(saltLength, wrappedKeyLength) - PackageFormat.HEADER_FIXED_BYTES

    /** Byte index where the wrapped-key ciphertext region begins (wrap AAD ends here). */
    fun wrapAadEnd(saltLength: Int): Int = wrappedKeyOffset(saltLength)
}

/**
 * Error taxonomy for decoding a v2 Portable Package.
 *
 * The categories are deliberately coarse and stable (UI maps them to user
 * copy; see PACKAGE_FORMAT.md §Errors / THREAT_MODEL.md). In particular:
 *
 * - **wrong PIN** and **corrupted ciphertext / wrapped key / nonce / AAD**
 *   are indistinguishable at the AEAD layer, so both surface as
 *   [AuthenticationFailed] ("wrong PIN or corrupted package"). The codec
 *   does NOT guess a more precise reason.
 * - Any authentication failure returns **no** plaintext and **no** partial
 *   [com.rescueauth.v2.export.VaultSnapshot].
 */
sealed class PackageCodecException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Bad magic, unknown reserved flags, unsupported formatVersion. */
    class UnsupportedFormat(message: String) : PackageCodecException(message)

    /** Unsupported cryptoVersion / algorithm id. */
    class UnsupportedCrypto(message: String) : PackageCodecException(message)

    /** KDF parameters present but outside the accepted decode range (pre-KDF rejection). */
    class InvalidKdfParameters(message: String) : PackageCodecException(message)

    /** Structurally broken header: truncation, inconsistent lengths, trailing garbage. */
    class MalformedPackage(message: String) : PackageCodecException(message)

    /** AEAD failure — wrong PIN or corrupted package (never distinguishes the two). */
    class AuthenticationFailed(message: String) : PackageCodecException(message)

    /** Crypto succeeded but the inner payload failed JSON parse or logical validation. */
    class LogicalPayloadInvalid(message: String, cause: Throwable? = null) : PackageCodecException(message)

    /**
     * Encode-side explicit capacity failure: the payload is logical-valid but
     * the serialized bytes exceed the package budget. The encoder fails
     * explicitly and safely BEFORE any oversized allocation — never OOM
     * (PACKAGE_FORMAT.md §Capacity). The logical validator already rejects
     * over-budget payloads via [com.rescueauth.v2.export.PackageValidator], so
     * this is a defense-in-depth guard for the exact post-serialization size.
     */
    class PackageTooLarge(message: String) : PackageCodecException(message)
}
