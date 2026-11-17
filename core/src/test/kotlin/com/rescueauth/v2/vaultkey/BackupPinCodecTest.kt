package com.rescueauth.v2.vaultkey

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BackupPinCodecTest {

    private val fastKdf = BackupPinCodec.KdfParams(
        memoryKiB = BackupPinCodec.MIN_MEMORY_KIB,
        iterations = 1,
        parallelism = 1,
    )

    private val sampleVaultKey = ByteArray(32) { (it * 7 + 3).toByte() }

    @Test
    fun `wrap and unwrap round trips exact 32-byte VaultKey`() {
        val envelope = BackupPinCodec.wrap(sampleVaultKey, "482910", fastKdf)
        assertEquals(BackupPinCodec.ENVELOPE_BYTES, envelope.size)

        val unwrapped = BackupPinCodec.unwrap(envelope, "482910")
        assertArrayEquals(sampleVaultKey, unwrapped)
        assertTrue(BackupPinCodec.verifyPin(envelope, "482910"))
    }

    @Test
    fun `production default KDF parameters round trip within runtime budget`() {
        val envelope = BackupPinCodec.wrap(sampleVaultKey, "13579086")
        val unwrapped = BackupPinCodec.unwrap(envelope, "13579086")
        assertArrayEquals(sampleVaultKey, unwrapped)
    }

    @Test
    fun `wrong PIN fails authentication without corrupting envelope`() {
        val envelope = BackupPinCodec.wrap(sampleVaultKey, "123456", fastKdf)
        assertFalse(BackupPinCodec.verifyPin(envelope, "654321"))
        assertFalse(BackupPinCodec.verifyPin(envelope, ""))
        assertFalse(BackupPinCodec.verifyPin(envelope, "12345a"))

        val thrown = runCatching { BackupPinCodec.unwrap(envelope, "654321") }.exceptionOrNull()
        assertTrue(thrown is BackupPinCodec.AuthenticationFailedException)
    }

    @Test
    fun `header tampering fails AEAD authentication`() {
        val envelope = BackupPinCodec.wrap(sampleVaultKey, "123456", fastKdf)
        // Flip iterations from 1 to 2 inside header (bytes 16..19): AAD mismatch must fail auth.
        val tampered = envelope.copyOf()
        ByteBuffer.wrap(tampered, 16, 4).order(ByteOrder.BIG_ENDIAN).putInt(2)

        val thrown = runCatching { BackupPinCodec.unwrap(tampered, "123456") }.exceptionOrNull()
        assertTrue(thrown is BackupPinCodec.AuthenticationFailedException)
    }

    @Test
    fun `out-of-budget KDF parameters in header are rejected before KDF runs`() {
        val envelope = BackupPinCodec.wrap(sampleVaultKey, "123456", fastKdf)
        val tampered = envelope.copyOf()
        // Set memoryKiB = 512 MiB (exceeds MAX_MEMORY_KIB = 128 MiB).
        ByteBuffer.wrap(tampered, 12, 4).order(ByteOrder.BIG_ENDIAN).putInt(512 * 1024)

        val thrown = runCatching { BackupPinCodec.unwrap(tampered, "123456") }.exceptionOrNull()
        assertTrue(thrown is BackupPinCodec.CorruptEnvelopeException)
    }

    @Test
    fun `truncated or wrong-magic envelope throws CorruptEnvelopeException`() {
        assertTrue(
            runCatching { BackupPinCodec.unwrap(ByteArray(10), "123456") }.exceptionOrNull()
                is BackupPinCodec.CorruptEnvelopeException,
        )
        val badMagic = ByteArray(BackupPinCodec.ENVELOPE_BYTES)
        assertTrue(
            runCatching { BackupPinCodec.unwrap(badMagic, "123456") }.exceptionOrNull()
                is BackupPinCodec.CorruptEnvelopeException,
        )
    }

    @Test
    fun `PIN validation enforces 6 to 128 ASCII digits`() {
        assertEquals(BackupPinCodec.PinIssue.EMPTY, BackupPinCodec.validateNewPin(""))
        assertEquals(BackupPinCodec.PinIssue.TOO_SHORT, BackupPinCodec.validateNewPin("12345"))
        assertEquals(BackupPinCodec.PinIssue.NON_DIGIT, BackupPinCodec.validateNewPin("12345a"))
        assertEquals(BackupPinCodec.PinIssue.NON_DIGIT, BackupPinCodec.validateNewPin("１２３４５６"))
        assertEquals(BackupPinCodec.PinIssue.TOO_LONG, BackupPinCodec.validateNewPin("1".repeat(129)))
        assertNull(BackupPinCodec.validateNewPin("000000"))
        assertNull(BackupPinCodec.validateNewPin("9".repeat(128)))
    }
}
