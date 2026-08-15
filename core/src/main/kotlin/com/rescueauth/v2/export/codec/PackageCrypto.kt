package com.rescueauth.v2.export.codec

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.XChaCha20Poly1305
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.security.SecureRandom

/**
 * Cryptographic primitives for the v2 Portable Package (Phase 3B).
 *
 * ## Dependency reuse (PACKAGE_FORMAT.md §Crypto / ADR-0002)
 *
 * Reuses the exact crypto stack that Phase 1 already validated and locked:
 *
 * - **Argon2id** (version 13) from Bouncy Castle 1.85 — same implementation
 *   used by the legacy importer;
 * - **XChaCha20-Poly1305** native AEAD from Bouncy Castle 1.85 — same
 *   implementation validated against the IETF draft-irtf-cfrg-xchacha-03
 *   official vector and the legacy Dart cross-vectors.
 *
 * No new crypto dependency and no hand-rolled primitive is introduced.
 *
 * ## Key hierarchy (see PACKAGE_FORMAT.md §Key hierarchy)
 *
 * ```
 * per-export PIN + random salt
 *          ↓ Argon2id
 *   PIN-derived wrapping key (KEK)
 *          ↓ XChaCha20-Poly1305  (wrap AEAD, AAD = header prefix)
 *   wrapped random 256-bit PackageKey
 *          ↓ XChaCha20-Poly1305  (payload AEAD, AAD = full header prefix)
 *   encrypted serialized VaultPackagePayload
 * ```
 *
 * The Export PIN is **never** used as the payload encryption key and is never
 * stored; each export derives a fresh KEK from a fresh random salt and a fresh
 * random PackageKey.
 *
 * ## Best-effort zeroization
 *
 * The JVM cannot guarantee physical-memory erasure. [zeroize] wipes every
 * secret buffer the codec owns (PIN bytes, KEK, unwrapped PackageKey,
 * plaintext payload) by overwriting it after use — this is documented as
 * **best-effort** (PACKAGE_FORMAT.md §Zeroization); it prevents trivial
 * long-lived retention but is not a physical-memory guarantee.
 */
internal object PackageCrypto {

    private val rng = SecureRandom()

    /**
     * Randomness seam used by the codec for all fresh material (salt, nonces,
     * PackageKey).
     *
     * The **default** is [SecureRandom] (a CSPRNG) — production never uses a
     * deterministic source. Tests may temporarily swap in a deterministic
     * generator via [withDeterministicRandom] to produce reproducible golden
     * fixtures / vectors (PACKAGE_FORMAT.md §Fixture / format stability). The
     * seam is `@Volatile` and only for test use; it is never on the production
     * default path.
     */
    @Volatile
    var randomSource: (Int) -> ByteArray = { n -> ByteArray(n).also { rng.nextBytes(it) } }

    /** Runs [block] with a deterministic random source; restores the default after. */
    fun <T> withDeterministicRandom(source: (Int) -> ByteArray, block: () -> T): T {
        val previous = randomSource
        randomSource = source
        try {
            return block()
        } finally {
            randomSource = previous
        }
    }

    /**
     * Generates a 256-bit cryptographically random PackageKey.
     * Uses [randomSource] (default [SecureRandom], a CSPRNG) on both JVM and Android.
     */
    fun newPackageKey(): ByteArray = randomSource(PackageFormat.PACKAGE_KEY_BYTES)

    /** Generates a random salt (default length). */
    fun newSalt(length: Int = PackageFormat.DEFAULT_SALT_BYTES): ByteArray = randomSource(length)

    /** Generates a random 24-byte XChaCha20 nonce. */
    fun newNonce(): ByteArray = randomSource(PackageFormat.XCHACHA20_NONCE_BYTES)

    /**
     * Argon2id key derivation (version 13).
     *
     * @param password the per-export PIN bytes (UTF-8)
     * @param params   validated KDF parameters (memoryKiB / iterations /
     *                 parallelism / outputLength) — callers MUST have run
     *                 header validation first (see [PackageHeaderParser])
     */
    fun argon2id(password: ByteArray, salt: ByteArray, memoryKiB: Int, iterations: Int, parallelism: Int, outputLength: Int): ByteArray {
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(iterations)
            .withMemoryAsKB(memoryKiB)
            .withParallelism(parallelism)
            .withSalt(salt)
        val generator = org.bouncycastle.crypto.generators.Argon2BytesGenerator()
        generator.init(builder.build())
        val out = ByteArray(outputLength)
        generator.generateBytes(password, out)
        return out
    }

    /** XChaCha20-Poly1305 encrypt with associated data; returns ct||tag. */
    fun aeadEncrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val aead = XChaCha20Poly1305()
        aead.init(true, ParametersWithIV(KeyParameter(key), nonce))
        if (aad.isNotEmpty()) aead.processAADBytes(aad, 0, aad.size)
        val out = ByteArray(aead.getOutputSize(plaintext.size))
        val len = aead.processBytes(plaintext, 0, plaintext.size, out, 0)
        val finalLen = aead.doFinal(out, len)
        return out.copyOf(len + finalLen)
    }

    /**
     * XChaCha20-Poly1305 decrypt with associated data.
     *
     * @throws PackageCodecException.AuthenticationFailed on any AEAD failure —
     *   wrong PIN and corrupted package are deliberately indistinguishable here.
     */
    fun aeadDecrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray {
        val aead = XChaCha20Poly1305()
        aead.init(false, ParametersWithIV(KeyParameter(key), nonce))
        if (aad.isNotEmpty()) aead.processAADBytes(aad, 0, aad.size)
        val out = ByteArray(aead.getOutputSize(ciphertextAndTag.size))
        return try {
            val len = aead.processBytes(ciphertextAndTag, 0, ciphertextAndTag.size, out, 0)
            val finalLen = aead.doFinal(out, len)
            out.copyOf(len + finalLen)
        } catch (e: InvalidCipherTextException) {
            zeroize(out)
            throw PackageCodecException.AuthenticationFailed(
                "authentication failed (wrong PIN or corrupted package)",
            )
        }
    }

    /** Best-effort wipe of a secret buffer (JVM does not guarantee physical erasure). */
    fun zeroize(bytes: ByteArray) {
        bytes.fill(0)
    }
}
