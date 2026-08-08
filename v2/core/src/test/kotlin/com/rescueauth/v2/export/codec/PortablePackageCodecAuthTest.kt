package com.rescueauth.v2.export.codec

import com.rescueauth.v2.export.PackageSourceMetadata
import com.rescueauth.v2.export.SnapshotBuilder
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultPackagePayload
import com.rescueauth.v2.export.VaultSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Authentication / corruption behavior of the Phase 3B codec.
 *
 * Requirement: any authentication failure returns NO partial plaintext and NO
 * partially parsed [VaultSnapshot]. Wrong PIN and corrupted ciphertext are
 * deliberately indistinguishable at the AEAD layer → both surface as
 * [PackageCodecException.AuthenticationFailed] (PACKAGE_FORMAT.md §Wrong PIN /
 * corruption, §Errors).
 */
class PortablePackageCodecAuthTest {

    private val pin = "test-pin-1234"

    private fun samplePayload(): VaultPackagePayload = VaultPackagePayload(
        logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
        packageId = "pkg-1",
        createdAt = "2024-01-01T00:00:00Z",
        source = PackageSourceMetadata(client = "test", appVersion = "1.0.0"),
        snapshot = VaultSnapshot(
            scope = SnapshotScope.AUTHENTICATOR_ONLY,
            accounts = listOf(
                SnapshotBuilder.account(
                    "acc-1", "GitHub", "alice",
                    totps = listOf(SnapshotBuilder.totp("totp-1", secret = "JBSWY3DPEHPK3PXP")),
                    recoverySets = listOf(
                        SnapshotBuilder.recoverySet(
                            "set-1", "Backup codes",
                            codes = listOf(SnapshotBuilder.recoveryCode("c1", "AAAA-BBBB", status = "UNUSED")),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun encode(): ByteArray = PortablePackageCodec.encode(samplePayload(), pin)

    @Test
    fun `wrong pin fails`() {
        val bytes = encode()
        assertThrows(PackageCodecException.AuthenticationFailed::class.java) {
            PortablePackageCodec.decode(bytes, "wrong-pin")
        }
    }

    @Test
    fun `corrupted wrapped package key fails`() {
        val bytes = encode()
        val envelope = PackageHeaderParser.parse(bytes)
        // Flip a byte inside the wrapped-key region.
        val mutated = bytes.copyOf()
        val wrappedKeyOffset = PackageLayout.wrappedKeyOffset(envelope.salt.size)
        mutated[wrappedKeyOffset] = (mutated[wrappedKeyOffset].toInt() xor 0x01).toByte()
        assertThrows(PackageCodecException.AuthenticationFailed::class.java) {
            PortablePackageCodec.decode(mutated, pin)
        }
    }

    @Test
    fun `corrupted payload ciphertext fails`() {
        val bytes = encode()
        val envelope = PackageHeaderParser.parse(bytes)
        // Flip a byte inside the payload ciphertext.
        val mutated = bytes.copyOf()
        val payloadStart = PackageLayout.headerEnd(envelope.salt.size, envelope.wrappedKey.size)
        mutated[payloadStart] = (mutated[payloadStart].toInt() xor 0x02).toByte()
        assertThrows(PackageCodecException.AuthenticationFailed::class.java) {
            PortablePackageCodec.decode(mutated, pin)
        }
    }

    @Test
    fun `corrupted nonce fails`() {
        val bytes = encode()
        val envelope = PackageHeaderParser.parse(bytes)
        // Flip a byte in the payload nonce region.
        val mutated = bytes.copyOf()
        val payloadNonceOffset = PackageLayout.payloadNonceOffset(envelope.salt.size, envelope.wrappedKey.size)
        mutated[payloadNonceOffset] = (mutated[payloadNonceOffset].toInt() xor 0x04).toByte()
        assertThrows(PackageCodecException.AuthenticationFailed::class.java) {
            PortablePackageCodec.decode(mutated, pin)
        }
    }

    @Test
    fun `authenticated header AAD tamper fails`() {
        val bytes = encode()
        val mutated = bytes.copyOf()
        // Tamper the KDF iterations field (a header field that is authenticated
        // via the payload AAD). The parse succeeds (value still in range), but
        // AEAD must fail.
        mutated[PackageLayout.OFF_KDF_ITERATIONS + 3] =
            (mutated[PackageLayout.OFF_KDF_ITERATIONS + 3].toInt() xor 0x01).toByte()
        assertThrows(PackageCodecException.AuthenticationFailed::class.java) {
            PortablePackageCodec.decode(mutated, pin)
        }
    }

    @Test
    fun `tampered format version fails as unsupported format`() {
        val bytes = encode()
        val mutated = bytes.copyOf()
        mutated[PackageLayout.OFF_FORMAT_VERSION] = 99
        assertThrows(PackageCodecException.UnsupportedFormat::class.java) {
            PortablePackageCodec.decode(mutated, pin)
        }
    }

    @Test
    fun `tampered crypto version fails as unsupported crypto`() {
        val bytes = encode()
        val mutated = bytes.copyOf()
        mutated[PackageLayout.OFF_CRYPTO_VERSION] = 99
        assertThrows(PackageCodecException.UnsupportedCrypto::class.java) {
            PortablePackageCodec.decode(mutated, pin)
        }
    }

    @Test
    fun `tampered headerLength fails as malformed`() {
        val bytes = encode()
        val mutated = bytes.copyOf()
        // Flip the low byte of the headerLength uint32 → declared != computed.
        mutated[PackageLayout.OFF_HEADER_LENGTH + 3] =
            (mutated[PackageLayout.OFF_HEADER_LENGTH + 3].toInt() xor 0x01).toByte()
        assertThrows(PackageCodecException.MalformedPackage::class.java) {
            PortablePackageCodec.decode(mutated, pin)
        }
    }

    @Test
    fun `truncated package fails as malformed`() {
        val bytes = encode()
        assertThrows(PackageCodecException.MalformedPackage::class.java) {
            PortablePackageCodec.decode(bytes.copyOf(bytes.size - 5), pin)
        }
    }

    @Test
    fun `trailing garbage is rejected`() {
        val bytes = encode()
        val padded = bytes + byteArrayOf(1, 2, 3)
        assertThrows(PackageCodecException.MalformedPackage::class.java) {
            PortablePackageCodec.decode(padded, pin)
        }
    }

    @Test
    fun `bad magic fails as unsupported format`() {
        val bytes = encode()
        val mutated = bytes.copyOf()
        mutated[0] = 'X'.code.toByte()
        assertThrows(PackageCodecException.UnsupportedFormat::class.java) {
            PortablePackageCodec.decode(mutated, pin)
        }
    }

    @Test
    fun `no partial plaintext on authentication failure`() {
        val bytes = encode()
        // Wrong PIN: the decode must throw — there is no partial payload
        // surface. The exception is the ONLY signal.
        val ex = assertThrows(PackageCodecException.AuthenticationFailed::class.java) {
            PortablePackageCodec.decode(bytes, "wrong-pin")
        }
        assertTrue(ex.message.orEmpty().isNotEmpty())
        // Corrupted wrapped key likewise.
        val envelope = PackageHeaderParser.parse(bytes)
        val mutated = bytes.copyOf()
        mutated[PackageLayout.wrappedKeyOffset(envelope.salt.size)] =
            (mutated[PackageLayout.wrappedKeyOffset(envelope.salt.size)].toInt() xor 0x01).toByte()
        assertThrows(PackageCodecException.AuthenticationFailed::class.java) {
            PortablePackageCodec.decode(mutated, pin)
        }
    }

    @Test
    fun `unsupported future formatVersion is rejected`() {
        val bytes = encode()
        val mutated = bytes.copyOf()
        mutated[PackageLayout.OFF_FORMAT_VERSION] = 2
        assertThrows(PackageCodecException.UnsupportedFormat::class.java) {
            PortablePackageCodec.decode(mutated, pin)
        }
    }

    @Test
    fun `unsupported future cryptoVersion is rejected`() {
        val bytes = encode()
        val mutated = bytes.copyOf()
        mutated[PackageLayout.OFF_CRYPTO_VERSION] = 2
        assertThrows(PackageCodecException.UnsupportedCrypto::class.java) {
            PortablePackageCodec.decode(mutated, pin)
        }
    }

    @Test
    fun `authentication failure message does not distinguish wrong pin from corruption`() {
        val bytes = encode()
        val ex1 = assertThrows(PackageCodecException.AuthenticationFailed::class.java) {
            PortablePackageCodec.decode(bytes, "wrong-pin")
        }
        val envelope = PackageHeaderParser.parse(bytes)
        val mutated = bytes.copyOf()
        mutated[PackageLayout.payloadNonceOffset(envelope.salt.size, envelope.wrappedKey.size)] =
            (mutated[PackageLayout.payloadNonceOffset(envelope.salt.size, envelope.wrappedKey.size)].toInt() xor 0x01).toByte()
        val ex2 = assertThrows(PackageCodecException.AuthenticationFailed::class.java) {
            PortablePackageCodec.decode(mutated, pin)
        }
        // Both must carry the same ambiguous message (UI decides the final copy).
        assertEquals(ex1.message, ex2.message)
    }
}
