package com.rescueauth.v2.update

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.util.Base64

/**
 * TEST-ONLY Ed25519 helper used to produce signed fixtures for the update
 * protocol unit tests.
 *
 * The keys generated here are random throw-away test keys — they are NEVER a
 * production key and never committed. Production signing uses a private key
 * held in CI secrets only (UPDATE_PROTOCOL.md / Issue #20 §8, §27).
 */
object TestEd25519 {

    data class KeyPair(
        val privateKey: Ed25519PrivateKeyParameters,
        val publicKeyRaw: ByteArray,
    ) {
        val publicKeyBase64: String = Base64.getEncoder().encodeToString(publicKeyRaw)
    }

    fun generateKeyPair(random: SecureRandom = SecureRandom()): KeyPair {
        val privateKey = Ed25519PrivateKeyParameters(random)
        val publicKey = privateKey.generatePublicKey()
        return KeyPair(privateKey, publicKey.encoded)
    }

    /** Signs [message] and returns the Base64-encoded raw 64-byte signature. */
    fun sign(privateKey: Ed25519PrivateKeyParameters, message: ByteArray): String {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(message, 0, message.size)
        val sig = signer.generateSignature()
        return Base64.getEncoder().encodeToString(sig)
    }

    /** Converts a raw 32-byte public key into an [Ed25519PublicKeyParameters]. */
    fun publicParameters(raw: ByteArray): Ed25519PublicKeyParameters =
        Ed25519PublicKeyParameters(raw, 0)
}
