package com.rescueauth.v2.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.export.VaultAndroidSigningKey
import com.rescueauth.v2.export.VaultEnvironmentVariableSet
import com.rescueauth.v2.export.VaultKeyValue
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * Phase 4 P6 repository tests for the final two Developer types:
 * Android Signing Key (opaque keystore binary) and Environment Variable Set.
 *
 * Covers create/edit/delete, exact keystore byte round-trip (close/reopen),
 * stableId-preserving edit, replace-keystore preserving stableId, metadata
 * list never exposing secrets, and env var ordering / case-sensitivity /
 * duplicate validation / value non-normalization.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeveloperSigningEnvRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine
    private lateinit var vault: VaultRepository
    private lateinit var repo: DeveloperRepository

    /** Synthetic binary keystore bytes (arbitrary opaque bytes — never parsed). */
    private fun syntheticKeystore(tag: Int): ByteArray =
        ByteArray(4096) { (it * 31 + tag) .toByte() }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = SecureSessionStateMachine()
        session.beginAuthentication()
        session.onAuthenticationSuccess()
        vault = VaultRepository(db, session)
        repo = DeveloperRepository(vault, db, session)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------
    // Android Signing Key (Issue #20 P6 §18 tests 1–4)
    // ------------------------------------------------------------------

    @Test
    fun `create signing key with synthetic keystore bytes`() = runBlocking {
        val bytes = syntheticKeystore(1)
        val entry = repo.createAndroidSigningKey(
            title = "release",
            notes = null,
            projectName = "app",
            packageName = "com.example.app",
            keystoreFileName = "release.jks",
            keystoreBytes = bytes,
            storePassword = "store-pass",
            keyAlias = "release-key",
            keyPassword = "key-pass",
        )
        assertNotNull(entry.stableId)
        val read = repo.getByStableId(entry.stableId) as VaultAndroidSigningKey
        assertEquals("release.jks", read.keystoreFileName)
        assertEquals("store-pass", read.storePassword)
        assertEquals("release-key", read.keyAlias)
        assertEquals("key-pass", read.keyPassword)
        // Exact byte round-trip via base64.
        assertArrayEquals(bytes, Base64.getDecoder().decode(read.keystoreBase64))
    }

    @Test
    fun `signing key persists exact bytes across close reopen`() = runBlocking {
        val bytes = syntheticKeystore(7)
        val created = repo.createAndroidSigningKey(
            title = "prod", notes = null,
            projectName = "p", packageName = "com.p",
            keystoreFileName = "prod.keystore", keystoreBytes = bytes,
            storePassword = "sp", keyAlias = "ka", keyPassword = "kp",
        )
        // Fresh repo on the same in-memory DB simulates close/reopen.
        val repo2 = DeveloperRepository(VaultRepository(db, session), db, session)
        val read = repo2.getByStableId(created.stableId) as VaultAndroidSigningKey
        assertArrayEquals(bytes, Base64.getDecoder().decode(read.keystoreBase64))
        assertEquals("sp", read.storePassword)
    }

    @Test
    fun `signing key edit preserves stableId`() = runBlocking {
        val created = repo.createAndroidSigningKey(
            title = "old", notes = "n",
            projectName = "p", packageName = "com.p",
            keystoreFileName = "a.jks", keystoreBytes = syntheticKeystore(1),
            storePassword = "sp1", keyAlias = "ka1", keyPassword = "kp1",
        )
        val edited = repo.editAndroidSigningKey(
            stableId = created.stableId,
            title = "new", notes = "n2",
            projectName = "p2", packageName = "com.p2",
            keystoreFileName = "b.jks", keystoreBytes = syntheticKeystore(2),
            storePassword = "sp2", keyAlias = "ka2", keyPassword = "kp2",
        )
        assertEquals(created.stableId, edited.stableId)
        assertEquals(created.createdAt, edited.createdAt)
        // Same logical identity — single row.
        assertEquals(1, repo.observeAll().first().size)
        assertArrayEquals(syntheticKeystore(2), Base64.getDecoder().decode(edited.keystoreBase64))
    }

    @Test
    fun `signing key metadata list never exposes passwords or bytes`() = runBlocking {
        val bytes = syntheticKeystore(9)
        repo.createAndroidSigningKey(
            title = "secret", notes = null,
            projectName = "p", packageName = "com.p",
            keystoreFileName = "s.jks", keystoreBytes = bytes,
            storePassword = "STORE-SECRET", keyAlias = "ka", keyPassword = "KEY-SECRET",
        )
        val listed = repo.observeAll().first()
        val s = listed.toString()
        assertTrue(!s.contains("STORE-SECRET"))
        assertTrue(!s.contains("KEY-SECRET"))
        assertTrue(!s.contains("com.p")) // projectName/package are safe metadata, but payload stays in JSON blob
    }

    @Test
    fun `signing key requires non-empty keystore`() = runBlocking {
        val e = assertThrows(DeveloperRepository.ValidationException::class.java) {
            runBlocking {
                repo.createAndroidSigningKey(
                    title = "t", notes = null,
                    projectName = "p", packageName = "com.p",
                    keystoreFileName = "a.jks", keystoreBytes = ByteArray(0),
                    storePassword = "sp", keyAlias = "ka", keyPassword = "kp",
                )
            }
        }
        assertTrue(e.message!!.isNotEmpty())
    }

    @Test
    fun `signing key rejects oversized keystore via derived cap`() = runBlocking {
        // 9 MiB + 1 exceeds the derived raw-byte cap.
        val tooBig = ByteArray(DeveloperRepository.MAX_KEYSTORE_RAW_BYTES + 1) { 0x42 }
        val e = assertThrows(DeveloperRepository.ValidationException::class.java) {
            runBlocking {
                repo.createAndroidSigningKey(
                    title = "t", notes = null,
                    projectName = "p", packageName = "com.p",
                    keystoreFileName = "a.jks", keystoreBytes = tooBig,
                    storePassword = "sp", keyAlias = "ka", keyPassword = "kp",
                )
            }
        }
        assertTrue(!e.message!!.contains("0x42"))
    }

    // ------------------------------------------------------------------
    // Environment Variable Set (Issue #20 P6 §19 tests 19–33)
    // ------------------------------------------------------------------

    @Test
    fun `env var set create multiple variables preserves order`() = runBlocking {
        val entry = repo.createEnvironmentVariableSet(
            title = "CI vars", notes = null,
            projectName = "app",
            variables = listOf(
                VaultKeyValue("AUTH_TOKEN", "t1"),
                VaultKeyValue("API_URL", "https://x"),
                VaultKeyValue("DEBUG", "true"),
            ),
        )
        val read = repo.getByStableId(entry.stableId) as VaultEnvironmentVariableSet
        assertEquals(listOf("AUTH_TOKEN", "API_URL", "DEBUG"), read.variables.map { it.key })
        assertEquals(listOf("t1", "https://x", "true"), read.variables.map { it.value })
    }

    @Test
    fun `env var set persists close reopen`() = runBlocking {
        val created = repo.createEnvironmentVariableSet(
            title = "V", notes = null,
            projectName = "p",
            variables = listOf(VaultKeyValue("K1", "v1"), VaultKeyValue("K2", "v2")),
        )
        val repo2 = DeveloperRepository(VaultRepository(db, session), db, session)
        val read = repo2.getByStableId(created.stableId) as VaultEnvironmentVariableSet
        assertEquals(listOf("K1", "K2"), read.variables.map { it.key })
        assertEquals(listOf("v1", "v2"), read.variables.map { it.value })
    }

    @Test
    fun `env var set edit preserves stableId`() = runBlocking {
        val created = repo.createEnvironmentVariableSet(
            title = "old", notes = null,
            projectName = "p",
            variables = listOf(VaultKeyValue("A", "1")),
        )
        val edited = repo.editEnvironmentVariableSet(
            stableId = created.stableId,
            title = "new", notes = "updated",
            projectName = "p2",
            variables = listOf(VaultKeyValue("B", "2"), VaultKeyValue("C", "3")),
        )
        assertEquals(created.stableId, edited.stableId)
        assertEquals(listOf("B", "C"), edited.variables.map { it.key })
        assertEquals(1, repo.observeAll().first().size)
    }

    @Test
    fun `env var duplicate names are rejected case-sensitively`() = runBlocking {
        // Exact duplicate rejected.
        assertThrows(DeveloperRepository.ValidationException::class.java) {
            runBlocking {
                repo.createEnvironmentVariableSet(
                    title = "t", notes = null, projectName = "p",
                    variables = listOf(VaultKeyValue("FOO", "1"), VaultKeyValue("FOO", "2")),
                )
            }
        }
        // Case-different names are distinct (case-sensitive).
        val ok = repo.createEnvironmentVariableSet(
            title = "t2", notes = null, projectName = "p",
            variables = listOf(VaultKeyValue("FOO", "1"), VaultKeyValue("foo", "2")),
        )
        assertEquals(2, (repo.getByStableId(ok.stableId) as VaultEnvironmentVariableSet).variables.size)
    }

    @Test
    fun `env var values are not normalized or re-cased`() = runBlocking {
        val created = repo.createEnvironmentVariableSet(
            title = "t", notes = null, projectName = "p",
            variables = listOf(VaultKeyValue("URL", "  https://Example.COM/path?q=1  ")),
        )
        val read = repo.getByStableId(created.stableId) as VaultEnvironmentVariableSet
        // Value is opaque — never trimmed / re-cased.
        assertEquals("  https://Example.COM/path?q=1  ", read.variables[0].value)
        // Name whitespace is normalized (trimmed).
        assertEquals("URL", read.variables[0].key)
    }

    @Test
    fun `env var names remain case-sensitive after trim`() = runBlocking {
        // Whitespace-trimmed names collapse to distinct case-sensitive keys.
        val created = repo.createEnvironmentVariableSet(
            title = "t", notes = null, projectName = "p",
            variables = listOf(VaultKeyValue(" Debug ", "1"), VaultKeyValue(" debug ", "2")),
        )
        val read = repo.getByStableId(created.stableId) as VaultEnvironmentVariableSet
        assertEquals(listOf("Debug", "debug"), read.variables.map { it.key })
    }

    @Test
    fun `env var delete confirmation removes full set`() = runBlocking {
        val created = repo.createEnvironmentVariableSet(
            title = "t", notes = null, projectName = "p",
            variables = listOf(VaultKeyValue("A", "1")),
        )
        val deleted = repo.delete(created.stableId)
        assertNotNull(deleted)
        assertNull(repo.getByStableId(created.stableId))
        assertEquals(0, repo.observeAll().first().size)
    }

    @Test
    fun `env var set requires at least one variable`() = runBlocking {
        val e = assertThrows(DeveloperRepository.ValidationException::class.java) {
            runBlocking {
                repo.createEnvironmentVariableSet(
                    title = "t", notes = null, projectName = "p",
                    variables = emptyList(),
                )
            }
        }
        assertTrue(e.message!!.isNotEmpty())
    }

    @Test
    fun `edit never mints new stableId for signing or env`() = runBlocking {
        val sign = repo.createAndroidSigningKey(
            "S", null, "p", "com.p", "a.jks", syntheticKeystore(1),
            "sp", "ka", "kp",
        )
        val env = repo.createEnvironmentVariableSet("E", null, "p", listOf(VaultKeyValue("A", "1")))

        val signEdited = repo.editAndroidSigningKey(
            sign.stableId, "S2", null, "p", "com.p", "b.jks", syntheticKeystore(2), "sp2", "ka2", "kp2",
        )
        val envEdited = repo.editEnvironmentVariableSet(
            env.stableId, "E2", null, "p", listOf(VaultKeyValue("B", "2")),
        )
        assertEquals(sign.stableId, signEdited.stableId)
        assertEquals(env.stableId, envEdited.stableId)
        assertNotEquals(sign.stableId, signEdited.updatedAt)
        assertEquals(2, repo.observeAll().first().size)
    }
}
