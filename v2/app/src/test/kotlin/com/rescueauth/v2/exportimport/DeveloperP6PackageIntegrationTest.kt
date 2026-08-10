package com.rescueauth.v2.exportimport

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.export.SelectedItemSet
import com.rescueauth.v2.export.VaultAndroidSigningKey
import com.rescueauth.v2.export.VaultEnvironmentVariableSet
import com.rescueauth.v2.export.VaultKeyValue
import com.rescueauth.v2.repository.DeveloperRepository
import com.rescueauth.v2.repository.ImportOutcome
import com.rescueauth.v2.repository.VaultRepository
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * Phase 4 P6 package + legacy-compatibility integration tests (Issue #20 §20).
 *
 * Proves the two new Developer types written through the real repository
 * naturally enter the Full Vault / Developer-only / Selected-item package
 * round-trips with:
 *
 * - keystore byte-for-byte equality,
 * - env names/order/values fully preserved,
 * - the same stableId with a changed payload remains a CONFLICT (unchanged
 *   merge semantics).
 *
 * The SAF picker is NOT exercised here (Issue #20 §22). Legacy-imported DB
 * entries are covered by the existing LegacyRepositoryIsolation / Legacy
 * fixtures tests plus a focused "legacy-mapped entry opens through the P6
 * repository path" assertion below (Issue #20 §14).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeveloperP6PackageIntegrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine
    private lateinit var vault: VaultRepository
    private lateinit var devRepo: DeveloperRepository
    private lateinit var service: ExportImportService

    private val pin: CharArray = "654321".toCharArray()

    private fun syntheticKeystore(tag: Int): ByteArray = ByteArray(4096) { (it * 17 + tag) .toByte() }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = SecureSessionStateMachine()
        session.beginAuthentication()
        session.onAuthenticationSuccess()
        vault = VaultRepository(db, session)
        devRepo = DeveloperRepository(vault, db, session)
        service = ExportImportService(vault, "test-1.0.0")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedSigning(): String =
        devRepo.createAndroidSigningKey(
            title = "release", notes = null,
            projectName = "app", packageName = "com.example.app",
            keystoreFileName = "release.jks", keystoreBytes = syntheticKeystore(3),
            storePassword = "store-pass", keyAlias = "release-key", keyPassword = "key-pass",
        ).stableId

    private suspend fun seedEnv(): String =
        devRepo.createEnvironmentVariableSet(
            title = "CI vars", notes = null,
            projectName = "app",
            variables = listOf(
                VaultKeyValue("AUTH_TOKEN", "token-1"),
                VaultKeyValue("API_URL", "https://example.com"),
                VaultKeyValue("DEBUG", "true"),
            ),
        ).stableId

    // ---- 34/37. Signing Key Full Vault round-trip with exact keystore bytes ----

    @Test
    fun `signing key full vault round trip keeps exact keystore bytes`() = runBlocking {
        val signId = seedSigning()
        seedEnv()
        val encoded = service.encodeFullVaultExport(pin)
        val decoded = service.decodeWithoutSession(encoded.bytes, pin)

        val sign = decoded.snapshot.developerEntries
            .filterIsInstance<VaultAndroidSigningKey>()
            .single()
        assertEquals(signId, sign.stableId)
        assertEquals("release.jks", sign.keystoreFileName)
        assertArrayEquals(syntheticKeystore(3), Base64.getDecoder().decode(sign.keystoreBase64))
        assertEquals("store-pass", sign.storePassword)
        assertEquals("release-key", sign.keyAlias)
        assertEquals("key-pass", sign.keyPassword)
    }

    // ---- 35/38. Developer-only round-trip ----

    @Test
    fun `signing and env survive developer-only round trip`() = runBlocking {
        seedSigning()
        seedEnv()
        val encoded = service.encodeExport(ExportScopeSpec.Developer, SelectedItemSet(), pin)
        val decoded = service.decodeWithoutSession(encoded.bytes, pin)
        assertEquals(
            com.rescueauth.v2.export.SnapshotScope.DEVELOPER_ONLY,
            decoded.snapshot.scope,
        )
        val types = decoded.snapshot.developerEntries.map { it::class.java.simpleName }.sorted()
        assertEquals(listOf("VaultAndroidSigningKey", "VaultEnvironmentVariableSet"), types)
    }

    // ---- 36/39. Selected-items round-trip ----

    @Test
    fun `signing and env survive selected items round trip`() = runBlocking {
        val signId = seedSigning()
        val envId = seedEnv()
        val selection = SelectedItemSet(selectedDeveloperStableIds = setOf(signId, envId))
        val encoded = service.encodeExport(ExportScopeSpec.SelectedItems, selection, pin)
        val decoded = service.decodeWithoutSession(encoded.bytes, pin)
        assertEquals(
            com.rescueauth.v2.export.SnapshotScope.SELECTED_ITEMS,
            decoded.snapshot.scope,
        )
        assertEquals(2, decoded.snapshot.developerEntries.size)
    }

    // ---- 40/41. Env names/order/values preserved ----

    @Test
    fun `env names order and values fully preserved in full vault round trip`() = runBlocking {
        val envId = seedEnv()
        val encoded = service.encodeFullVaultExport(pin)
        val decoded = service.decodeWithoutSession(encoded.bytes, pin)
        val env = decoded.snapshot.developerEntries
            .filterIsInstance<VaultEnvironmentVariableSet>()
            .single()
        assertEquals(envId, env.stableId)
        assertEquals(listOf("AUTH_TOKEN", "API_URL", "DEBUG"), env.variables.map { it.key })
        assertEquals(listOf("token-1", "https://example.com", "true"), env.variables.map { it.value })
    }

    // ---- 42/43. Legacy-mapped entry opens through the P6 repository path ----

    @Test
    fun `legacy-mapped signing key opens through repository read path`() = runBlocking {
        // Simulate a DB row produced by the Phase 5B legacy mapper: build the
        // logical model directly (as LegacyVaultSnapshotMapper would) and insert
        // it through the shared mapper so it enters the same table/format.
        val now = java.time.Instant.now().toString()
        val legacyMapped = VaultAndroidSigningKey(
            stableId = "legacy-signing-1",
            projectName = "legacy-app",
            packageName = "com.legacy.app",
            keystoreFileName = "legacy.jks",
            keystoreBase64 = Base64.getEncoder().encodeToString(syntheticKeystore(11)),
            storePassword = "legacy-sp",
            keyAlias = "legacy-key",
            keyPassword = "legacy-kp",
            title = "Legacy signing",
            notes = null,
            createdAt = now,
            updatedAt = now,
        )
        // Insert a row exactly as the Phase 5B legacy mapper would (shared mapper).
        val entity = com.rescueauth.v2.repository.DeveloperMappers.toEntity(legacyMapped, 0)
        db.developerEntryDao().insertAll(listOf(entity))

        // P6 path: read + export must work on this legacy-imported entry.
        val read = devRepo.getByStableId("legacy-signing-1") as VaultAndroidSigningKey
        assertEquals("com.legacy.app", read.packageName)
        assertArrayEquals(syntheticKeystore(11), Base64.getDecoder().decode(read.keystoreBase64))
        // It appears in the Developer list metadata.
        assertEquals(1, devRepo.observeAll().first().size)
    }

    @Test
    fun `legacy-mapped env var set opens through repository read path`() = runBlocking {
        val now = java.time.Instant.now().toString()
        val legacyMapped = VaultEnvironmentVariableSet(
            stableId = "legacy-env-1",
            projectName = "legacy-app",
            variables = listOf(
                VaultKeyValue("DB_URL", "jdbc:postgres://x"),
                VaultKeyValue("SECRET_KEY", "legacy-secret"),
            ),
            title = "Legacy env",
            notes = null,
            createdAt = now,
            updatedAt = now,
        )
        val entity = com.rescueauth.v2.repository.DeveloperMappers.toEntity(legacyMapped, 1)
        db.developerEntryDao().insertAll(listOf(entity))
        val read = devRepo.getByStableId("legacy-env-1") as VaultEnvironmentVariableSet
        assertEquals(listOf("DB_URL", "SECRET_KEY"), read.variables.map { it.key })
        assertEquals(listOf("jdbc:postgres://x", "legacy-secret"), read.variables.map { it.value })
    }

    // ---- 44. Developer conflict semantics unchanged ----

    @Test
    fun `same signing stableId changed payload remains a conflict`() = runBlocking {
        val signId = seedSigning()
        val encoded = service.encodeFullVaultExport(pin)
        val decoded = service.decodeWithoutSession(encoded.bytes, pin)

        // Edit the local entry so the same stableId now carries a changed keystore.
        devRepo.editAndroidSigningKey(
            stableId = signId,
            title = "release", notes = null,
            projectName = "app", packageName = "com.example.app",
            keystoreFileName = "release.jks", keystoreBytes = syntheticKeystore(99),
            storePassword = "store-pass", keyAlias = "release-key", keyPassword = "key-pass",
        )

        val outcome = vault.applySnapshot(decoded.snapshot, "pkg-conflict")
        assertTrue("expected Blocked, got $outcome", outcome is ImportOutcome.Blocked)
        assertEquals(1, (outcome as ImportOutcome.Blocked).result.conflicts)
    }

    // ---- 45. Second import idempotent ----

    @Test
    fun `reimport of the same signing package is idempotent`() = runBlocking {
        seedSigning()
        val encoded = service.encodeFullVaultExport(pin)
        val decoded = service.decodeWithoutSession(encoded.bytes, pin)

        val db2 = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val vault2 = VaultRepository(db2, session)
            val o1 = vault2.applySnapshot(decoded.snapshot, "pkg-1")
            assertTrue(o1 is ImportOutcome.Applied)
            assertEquals(1, (o1 as ImportOutcome.Applied).result.insertedDeveloperEntries)

            val o2 = vault2.applySnapshot(decoded.snapshot, "pkg-1")
            assertTrue(o2 is ImportOutcome.Applied)
            assertEquals(0, (o2 as ImportOutcome.Applied).result.insertedDeveloperEntries)
            assertEquals(1, (o2 as ImportOutcome.Applied).result.duplicates)
        } finally {
            db2.close()
        }
    }
}
