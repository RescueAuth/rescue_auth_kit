package com.rescueauth.v2.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VaultKey lifecycle tests with a JVM fake crypto (no Android Keystore).
 */
class VaultKeyManagerTest {

    private fun freshManager() = VaultKeyManager(FakeVaultKeyCrypto())

    @Test
    fun `generate and unwrap round trips the same key`() {
        val mgr = freshManager()
        val key = mgr.generateAndWrap()
        assertNotNull(key)
        assertTrue(mgr.hasVaultKey)

        val unwrapped = mgr.unwrap()
        assertArrayEquals(key, unwrapped)

        unwrapped.fill(0)
        key.fill(0)
    }

    @Test
    fun `no vault key before first run throws NoVaultKeyException`() {
        val mgr = freshManager()
        assertFalse(mgr.hasVaultKey)
        try {
            mgr.unwrap()
            assertTrue("expected NoVaultKeyException", false)
        } catch (e: VaultKeyManager.NoVaultKeyException) {
            // expected
        }
    }

    @Test
    fun `deleteWrapped removes key and blob`() {
        val mgr = freshManager()
        mgr.generateAndWrap()
        assertTrue(mgr.hasVaultKey)
        mgr.deleteWrapped()
        assertFalse(mgr.hasVaultKey)
    }

    @Test
    fun `keystore invalidation surfaces as KeyInvalidatedException`() {
        val crypto = FakeVaultKeyCrypto()
        val mgr = VaultKeyManager(crypto)
        mgr.generateAndWrap()
        crypto.invalidateOnUnwrap = true
        try {
            mgr.unwrap()
            assertTrue("expected KeyInvalidatedException", false)
        } catch (e: VaultKeyManager.KeyInvalidatedException) {
            // expected
        }
    }

    @Test
    fun `no-auth during wrap maps to AuthRequiredException not KeystoreUnavailable`() {
        val crypto = FakeVaultKeyCrypto().apply { requireAuthOnWrap = true }
        val mgr = VaultKeyManager(crypto)
        try {
            mgr.generateAndWrap()
            assertTrue("expected AuthRequiredException", false)
        } catch (e: VaultKeyManager.AuthRequiredException) {
            // expected — must NOT be KeystoreUnavailable and must NOT crash.
        } catch (e: Exception) {
            assertTrue("wrong exception type: ${e.javaClass.simpleName}", false)
        }
    }

    @Test
    fun `no-auth during unwrap maps to AuthRequiredException not KeyInvalidated`() {
        val crypto = FakeVaultKeyCrypto().apply { requireAuthOnUnwrap = true }
        val mgr = VaultKeyManager(crypto)
        mgr.generateAndWrap()
        try {
            mgr.unwrap()
            assertTrue("expected AuthRequiredException", false)
        } catch (e: VaultKeyManager.AuthRequiredException) {
            // expected.
        } catch (e: Exception) {
            assertTrue("wrong exception type: ${e.javaClass.simpleName}", false)
        }
    }

    @Test
    fun `corrupt blob throws KeyInvalidatedException`() {
        val inner = FakeVaultKeyCrypto()
        val crypto = object : VaultKeyCrypto {
            override fun wrap(vaultKey: ByteArray, persist: (String) -> Unit) = inner.wrap(vaultKey, persist)
            override fun readWrapped(): String? {
                val raw = inner.readWrapped() ?: return null
                // Flip a char to corrupt.
                return if (raw.last() == 'A') raw.dropLast(1) + "B" else raw.dropLast(1) + "A"
            }
            override fun persistBlob(blob: String) = inner.persistBlob(blob)
            override fun unwrap(blob: String): ByteArray = inner.unwrap(blob)
            override fun reset() = inner.reset()
            override fun deletePersisted() = inner.deletePersisted()
        }
        val mgr = VaultKeyManager(crypto)
        mgr.generateAndWrap()
        try {
            mgr.unwrap()
            assertTrue("expected KeyInvalidatedException", false)
        } catch (e: VaultKeyManager.KeyInvalidatedException) {
            // expected
        }
    }
}
