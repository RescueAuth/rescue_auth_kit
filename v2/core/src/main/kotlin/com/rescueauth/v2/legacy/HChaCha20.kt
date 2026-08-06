package com.rescueauth.v2.legacy

/**
 * HChaCha20 with the EXACT semantics of the Dart `cryptography` package
 * (v2.9.0) used by the legacy Flutter app (tag `v1.2.0`).
 *
 * IMPORTANT COMPATIBILITY NOTE
 * ----------------------------
 * The Dart `cryptography` package's HChaCha20 follows the standard IETF
 * XChaCha draft construction (state[12..15] = nonce words, 20 rounds with no
 * final addition, subkey = words 0..3 ++ 12..15). Empirically verified: with
 * key=00..1f and nonce=00000009 0000004a 00000000 31415927, both Dart and the
 * standard reference output `82413b42...`. The only subtlety is that the
 * quarter round rotates LEFT (rotl), not right.
 */
object HChaCha20 {

    /** @return 32-byte subkey derived from [key] (32 bytes) and [nonce16] (16 bytes). */
    fun deriveSubkey(key: ByteArray, nonce16: ByteArray): ByteArray {
        require(key.size == 32) { "HChaCha20 key must be 32 bytes" }
        require(nonce16.size == 16) { "HChaCha20 nonce must be 16 bytes" }

        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        for (i in 0 until 8) {
            state[4 + i] = leInt(key, i * 4)
        }
        // Dart layout: nonce words fill state[12..15] (standard IETF layout).
        state[12] = leInt(nonce16, 0)
        state[13] = leInt(nonce16, 4)
        state[14] = leInt(nonce16, 8)
        state[15] = leInt(nonce16, 12)

        // 20 rounds, no final addition (Dart: addAndXor = false).
        for (i in 0 until 10) {
            doubleRound(state)
        }

        val subkey = ByteArray(32)
        for (i in 0 until 4) putLeInt(subkey, i * 4, state[i])
        for (i in 0 until 4) putLeInt(subkey, 16 + i * 4, state[12 + i])
        return subkey
    }

    private fun doubleRound(x: IntArray) {
        quarterRound(x, 0, 4, 8, 12)
        quarterRound(x, 1, 5, 9, 13)
        quarterRound(x, 2, 6, 10, 14)
        quarterRound(x, 3, 7, 11, 15)
        quarterRound(x, 0, 5, 10, 15)
        quarterRound(x, 1, 6, 11, 12)
        quarterRound(x, 2, 7, 8, 13)
        quarterRound(x, 3, 4, 9, 14)
    }

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] = x[a] + x[b]; x[d] = rotl(x[d] xor x[a], 16)
        x[c] = x[c] + x[d]; x[b] = rotl(x[b] xor x[c], 12)
        x[a] = x[a] + x[b]; x[d] = rotl(x[d] xor x[a], 8)
        x[c] = x[c] + x[d]; x[b] = rotl(x[b] xor x[c], 7)
    }

    private fun rotl(v: Int, n: Int): Int = Integer.rotateLeft(v, n)

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)

    private fun putLeInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xff).toByte()
        b[off + 1] = ((v ushr 8) and 0xff).toByte()
        b[off + 2] = ((v ushr 16) and 0xff).toByte()
        b[off + 3] = ((v ushr 24) and 0xff).toByte()
    }
}
