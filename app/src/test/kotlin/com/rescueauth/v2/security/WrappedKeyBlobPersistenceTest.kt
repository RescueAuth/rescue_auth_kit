package com.rescueauth.v2.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The wrapped VaultKey blob is the ONLY thing that can decrypt the vault.
 *
 * Reported concern: the app must never conclude "no vault yet" while a vault
 * exists, because that path mints a fresh key and overwrites the blob —
 * rendering the existing database permanently unrecoverable.
 *
 * Two properties close it:
 *   1. presence is decided by the file EXISTING, not by its content being
 *      readable/decodeable (a blank or half-written blob still means "a
 *      vault exists");
 *   2. the blob is WRITE-ONCE — persisting over an existing blob is refused
 *      at the filesystem level, so even a misjudged entry point cannot
 *      destroy the key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WrappedKeyBlobPersistenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun blobFile(): File =
        File(context.filesDir, "vault_key_wrapped.b64")

    private fun cleanCrypto(): AndroidKeystoreVaultKeyCrypto {
        blobFile().delete()
        return AndroidKeystoreVaultKeyCrypto(
            context,
            userAuthenticationRequired = false,
        )
    }

    @Test
    fun `no persisted vault key on a clean install`() {
        val crypto = cleanCrypto()
        assertFalse("clean install must report no vault", crypto.hasPersisted())
    }

    @Test
    fun `persisting once then reporting presence`() {
        val crypto = cleanCrypto()
        crypto.persistBlob("blob-one")

        assertTrue("persisted blob must count as an existing vault", crypto.hasPersisted())
        assertEquals("blob-one", crypto.readWrapped())
    }

    @Test
    fun `persisting over an existing blob is refused and keeps the original`() {
        val crypto = cleanCrypto()
        crypto.persistBlob("original-key" )

        val thrown = try {
            crypto.persistBlob("replacement-key")
            false
        } catch (e: Exception) {
            true
        }

        assertTrue("overwriting the wrapped VaultKey must fail closed", thrown)
        assertEquals(
            "the original wrapped key must survive the refused overwrite",
            "original-key",
            crypto.readWrapped(),
        )
    }

    @Test
    fun `a blank blob still counts as an existing vault`() {
        val crypto = cleanCrypto()
        // Simulate a truncated / empty write from an interrupted install.
        blobFile().writeText("")

        assertTrue(
            "presence must be judged by the file, not by decodable content",
            crypto.hasPersisted(),
        )
    }

    @Test
    fun `deletePersisted clears presence so a deliberate reset can recreate`() {
        val crypto = cleanCrypto()
        crypto.persistBlob("blob-one")
        assertTrue(crypto.hasPersisted())

        crypto.deletePersisted()

        assertFalse("an explicit delete must allow a later create", crypto.hasPersisted())
        assertFalse(blobFile().exists())
    }
}
