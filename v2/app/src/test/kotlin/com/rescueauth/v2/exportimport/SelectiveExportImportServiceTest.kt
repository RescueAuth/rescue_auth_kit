package com.rescueauth.v2.exportimport

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.export.SelectedItemSet
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultAccount
import com.rescueauth.v2.export.VaultAndroidSigningKey
import com.rescueauth.v2.export.VaultApiCredential
import com.rescueauth.v2.export.VaultGenericSecret
import com.rescueauth.v2.export.VaultKeyValue
import com.rescueauth.v2.export.VaultPackagePayload
import com.rescueauth.v2.export.VaultRecoveryCode
import com.rescueauth.v2.export.VaultRecoveryCodeSet
import com.rescueauth.v2.export.VaultSnapshot
import com.rescueauth.v2.export.VaultSshKey
import com.rescueauth.v2.export.VaultEnvironmentVariableSet
import com.rescueauth.v2.export.codec.PackageFormat
import com.rescueauth.v2.export.codec.PortablePackageCodec
import com.rescueauth.v2.repository.ImportOutcome
import com.rescueauth.v2.repository.MergeTestData
import com.rescueauth.v2.repository.VaultRepository
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 4 P5 — Selective Export / Import service integration tests.
 *
 * Covers the export integration tests (14–24) and import integration tests
 * (25–37) at the service layer:
 *
 * 14. Authenticator package encode/decode round-trip
 * 15. Developer package five-type round-trip
 * 16. selected TOTP round-trip
 * 17. selected Recovery Set preserves USED/usedAt
 * 18. selected Developer entry exact round-trip
 * 19. per-export PIN policy unchanged
 * 20. every export scope requires fresh re-auth (ViewModel-level, see
 *     ExportImportViewModelP5Test)
 * 21. auth cancel creates no SAF document (ViewModel-level)
 * 22. second export requires new auth (ViewModel-level)
 * 23. stale selection before final snapshot fails safely
 * 24. 16 MiB package capacity still enforced
 * 25. package FULL → import only Authenticator
 * 26. package FULL → import only Developer
 * 27. package FULL → import selected TOTP
 * 28. package FULL → import selected Recovery Set
 * 29. package FULL → import selected Developer
 * 30. selected package can only further narrow contents
 * 31. unselected conflict ignored
 * 32. selected conflict blocked
 * 33. Recovery divergence selected → blocked
 * 34. final apply re-plan catches newly introduced conflict
 * 35. repeated selective import idempotent
 * 36. ImportRecord only after success
 * 37. cancel clears decoded plaintext
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectiveExportImportServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine
    private lateinit var vault: VaultRepository
    private lateinit var service: ExportImportService

    private val pin: CharArray = "123456".toCharArray()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = SecureSessionStateMachine()
        session.beginAuthentication()
        session.onAuthenticationSuccess()
        vault = VaultRepository(db, session)
        service = ExportImportService(vault, "test-1.0.0")
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A vault with two accounts + all five developer types + recovery codes. */
    private suspend fun seedFull(): VaultSnapshot {
        val snapshot = MergeTestData.fullSnapshot(
            MergeTestData.account(
                "acc-1",
                serviceName = "GitHub",
                accountName = "alice",
                totps = listOf(
                    MergeTestData.totp("totp-1", secret = "JBSWY3DPEHPK3PXP"),
                    MergeTestData.totp("totp-2", secret = "4F6VS6KX3UXWY2FQ"),
                ),
                recoverySets = listOf(
                    MergeTestData.recoverySet(
                        "set-1",
                        title = "GitHub codes",
                        codes = listOf(
                            MergeTestData.recoveryCode("c-1", "111-111", status = "UNUSED"),
                            MergeTestData.recoveryCode(
                                "c-2",
                                "222-222",
                                status = "USED",
                                usedAt = "2024-02-02T00:00:00Z",
                            ),
                        ),
                    ),
                ),
            ),
            MergeTestData.account(
                "acc-2",
                serviceName = "GitLab",
                accountName = "bob",
                totps = listOf(MergeTestData.totp("totp-3", secret = "NBSWY3DPEHPK3PXP")),
            ),
            developers = listOf(
                MergeTestData.signingKey("dev-sk", keystoreBase64 = "AAECAwQFBgc="),
                MergeTestData.apiCredential("dev-api"),
                MergeTestData.sshKey("dev-ssh"),
                MergeTestData.envVarSet("dev-env", variables = listOf(VaultKeyValue("API_KEY", "x"))),
                MergeTestData.genericSecret("dev-generic", fields = listOf(VaultKeyValue("token", "t-1"))),
            ),
        )
        vault.applySnapshot(snapshot, "seed")
        return snapshot
    }

    // ---- 14. Authenticator package encode/decode round-trip ----

    @Test
    fun `authenticator package round-trips without developer`() = runBlocking {
        seedFull()
        val encoded = service.encodeExport(ExportScopeSpec.Authenticator, SelectedItemSet(), pin)
        assertEquals(SnapshotScope.AUTHENTICATOR_ONLY, encoded.scope)

        val decoded = PortablePackageCodec.decode(encoded.bytes, pin)
        assertEquals(SnapshotScope.AUTHENTICATOR_ONLY, decoded.snapshot.scope)
        assertEquals(2, decoded.snapshot.accounts.size)
        assertTrue(decoded.snapshot.developerEntries.isEmpty())
        // TOTP + recovery codes preserved.
        assertEquals(3, decoded.snapshot.accounts.sumOf { it.totpCredentials.size })
        assertEquals(1, decoded.snapshot.accounts.sumOf { it.recoveryCodeSets.size })
        assertEquals(2, decoded.snapshot.accounts.sumOf { it.recoveryCodeSets.sumOf { s -> s.codes.size } })
    }

    // ---- 15. Developer package five-type round-trip ----

    @Test
    fun `developer package five-type round-trips without authenticator`() = runBlocking {
        seedFull()
        val encoded = service.encodeExport(ExportScopeSpec.Developer, SelectedItemSet(), pin)
        assertEquals(SnapshotScope.DEVELOPER_ONLY, encoded.scope)

        val decoded = PortablePackageCodec.decode(encoded.bytes, pin)
        assertEquals(SnapshotScope.DEVELOPER_ONLY, decoded.snapshot.scope)
        assertTrue(decoded.snapshot.accounts.isEmpty())
        assertEquals(5, decoded.snapshot.developerEntries.size)
        // Exact logical preservation.
        val byStable = decoded.snapshot.developerEntries.associateBy { it.stableId }
        assertTrue(byStable["dev-sk"] is VaultAndroidSigningKey)
        assertTrue(byStable["dev-api"] is VaultApiCredential)
        assertTrue(byStable["dev-ssh"] is VaultSshKey)
        assertTrue(byStable["dev-env"] is VaultEnvironmentVariableSet)
        assertTrue(byStable["dev-generic"] is VaultGenericSecret)
        assertEquals(
            "AAECAwQFBgc=",
            (byStable["dev-sk"] as VaultAndroidSigningKey).keystoreBase64,
        )
        assertEquals(
            "t-1",
            (byStable["dev-generic"] as VaultGenericSecret).fields.first { it.key == "token" }.value,
        )
    }

    // ---- 16. selected TOTP round-trip ----

    @Test
    fun `selected TOTP round-trips with parent metadata only`() = runBlocking {
        seedFull()
        val selection = SelectedItemSet(selectedTotpStableIds = setOf("totp-2"))
        val encoded = service.encodeExport(ExportScopeSpec.SelectedItems, selection, pin)
        assertEquals(SnapshotScope.SELECTED_ITEMS, encoded.scope)

        val decoded = PortablePackageCodec.decode(encoded.bytes, pin)
        assertEquals(1, decoded.snapshot.accounts.size)
        val acc = decoded.snapshot.accounts.single()
        assertEquals("acc-1", acc.stableId)
        assertEquals("GitHub", acc.serviceName) // parent metadata present
        assertEquals(listOf("totp-2"), acc.totpCredentials.map { it.stableId })
        assertTrue(acc.recoveryCodeSets.isEmpty())
        assertTrue(decoded.snapshot.developerEntries.isEmpty())
    }

    // ---- 17. selected Recovery Set preserves USED/usedAt ----

    @Test
    fun `selected recovery set preserves USED and usedAt`() = runBlocking {
        seedFull()
        val selection = SelectedItemSet(selectedRecoverySetStableIds = setOf("set-1"))
        val encoded = service.encodeExport(ExportScopeSpec.SelectedItems, selection, pin)
        val decoded = PortablePackageCodec.decode(encoded.bytes, pin)

        val set = decoded.snapshot.accounts.single().recoveryCodeSets.single()
        assertEquals("set-1", set.stableId)
        assertEquals(2, set.codes.size)
        val used = set.codes.first { it.stableId == "c-2" }
        assertEquals("USED", used.status)
        assertEquals("2024-02-02T00:00:00Z", used.usedAt)
        val unused = set.codes.first { it.stableId == "c-1" }
        assertEquals("UNUSED", unused.status)
    }

    // ---- 18. selected Developer entry exact round-trip ----

    @Test
    fun `selected developer entry exact round-trip`() = runBlocking {
        seedFull()
        val selection = SelectedItemSet(selectedDeveloperStableIds = setOf("dev-api"))
        val encoded = service.encodeExport(ExportScopeSpec.SelectedItems, selection, pin)
        val decoded = PortablePackageCodec.decode(encoded.bytes, pin)

        assertEquals(1, decoded.snapshot.developerEntries.size)
        val api = decoded.snapshot.developerEntries.single() as VaultApiCredential
        assertEquals("dev-api", api.stableId)
        assertEquals("stripe", api.serviceName)
        assertEquals("sk_test_123", api.apiKey)
        assertEquals("secret-abc", api.apiSecret)
        assertTrue(decoded.snapshot.accounts.isEmpty())
    }

    // ---- 19. per-export PIN policy unchanged ----

    @Test
    fun `per-export PIN policy is unchanged for every scope`() = runBlocking {
        seedFull()
        for (scope in listOf(
            ExportScopeSpec.FullVault,
            ExportScopeSpec.Authenticator,
            ExportScopeSpec.Developer,
        )) {
            val encoded = service.encodeExport(scope, SelectedItemSet(), pin)
            // Wrong PIN cannot decode regardless of scope.
            try {
                PortablePackageCodec.decode(encoded.bytes, "654321".toCharArray())
                fail("expected AuthenticationFailed for scope $scope")
            } catch (e: com.rescueauth.v2.export.codec.PackageCodecException.AuthenticationFailed) {
                // expected
            }
            // Correct PIN decodes.
            PortablePackageCodec.decode(encoded.bytes, pin)
        }
    }

    // ---- 23. stale selection before final snapshot fails safely ----

    @Test
    fun `stale selection fails safely`() = runBlocking {
        seedFull()
        // Select a TOTP that does not exist in the vault.
        val selection = SelectedItemSet(selectedTotpStableIds = setOf("totp-DELETED"))
        try {
            service.encodeExport(ExportScopeSpec.SelectedItems, selection, pin)
            fail("expected StaleSelectionException")
        } catch (e: StaleSelectionException) {
            assertTrue(e.missingStableIds.any { it.contains("totp-DELETED") })
        }

        // Empty selection also fails.
        try {
            service.encodeExport(ExportScopeSpec.SelectedItems, SelectedItemSet(), pin)
            fail("expected EmptySelectionException")
        } catch (e: EmptySelectionException) {
            // expected
        }
    }

    // ---- 24. 16 MiB package capacity still enforced -------

    @Test
    fun `16 MiB package capacity still enforced for selected exports`() = runBlocking {
        // The per-asset keystore cap is 12 MiB of base64; the WHOLE package
        // budget is 16 MiB minus header/tag. Two keystores that individually
        // pass the per-asset cap exceed the total serialized budget and must be
        // rejected by the validator/codec (PACKAGE_FORMAT.md §Capacity).
        // Built at the payload level to avoid Robolectric's CursorWindow limit.
        val b64 = "A".repeat(8 * 1024 * 1024) // ~8 MiB base64 each, under per-asset cap
        val snapshot = MergeTestData.fullSnapshot(
            developers = listOf(
                MergeTestData.signingKey("dev-huge-1", keystoreBase64 = b64),
                MergeTestData.signingKey("dev-huge-2", keystoreBase64 = b64),
            ),
        )
        val payload = VaultPackagePayload(
            logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
            packageId = "pkg-huge",
            createdAt = "2024-01-01T00:00:00Z",
            snapshot = snapshot,
        )
        try {
            PortablePackageCodec.encode(payload, pin)
            fail("expected PackageTooLarge / validation failure")
        } catch (e: com.rescueauth.v2.export.codec.PackageCodecException.PackageTooLarge) {
            // expected — codec post-serialization guard
        } catch (e: com.rescueauth.v2.export.PackageValidator.ValidationException) {
            // expected — validator budget rejects before encode
        }
    }

    // ------------------------------------------------------------------
    // Import integration (25–37)
    // ------------------------------------------------------------------

    private fun makePayload(packageId: String, snapshot: VaultSnapshot) = VaultPackagePayload(
        logicalSchemaVersion = 1,
        packageId = packageId,
        createdAt = "2024-01-01T00:00:00Z",
        snapshot = snapshot,
    )

    private fun encodedBytes(snapshot: VaultSnapshot, packageId: String = "pkg"): ByteArray =
        PortablePackageCodec.encode(makePayload(packageId, snapshot), pin)

    // ---- 25. package FULL → import only Authenticator ----

    @Test
    fun `import only authenticator from a full package`() = runBlocking {
        seedFull()
        val bytes = encodedBytes(service.decodeWithoutSession(service.encodeExport(ExportScopeSpec.FullVault, SelectedItemSet(), pin).bytes, pin).snapshot, "full")

        // Fresh destination vault.
        val db2 = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val session2 = SecureSessionStateMachine()
            session2.beginAuthentication()
            session2.onAuthenticationSuccess()
            val vault2 = VaultRepository(db2, session2)
            val svc2 = ExportImportService(vault2, "test")

            svc2.decodeForPreview(bytes, pin)
            val preset = svc2.importPreset(ImportScopeSpec.Authenticator)!!
            val preview = svc2.filterActiveImport(preset)
            assertEquals(2, preview.accounts)
            assertEquals(0, preview.developerSummary.total)

            val outcome = svc2.confirmImport()
            assertTrue(outcome is ImportOutcome.Applied)
            val dest = vault2.buildDestinationSnapshot()
            assertEquals(2, dest.accounts.size)
            assertTrue(dest.developerEntries.isEmpty())
        } finally {
            db2.close()
        }
    }

    // ---- 26. package FULL → import only Developer ----

    @Test
    fun `import only developer from a full package`() = runBlocking {
        seedFull()
        val bytes = encodedBytes(service.decodeWithoutSession(service.encodeExport(ExportScopeSpec.FullVault, SelectedItemSet(), pin).bytes, pin).snapshot, "full")

        val db2 = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val session2 = SecureSessionStateMachine()
            session2.beginAuthentication()
            session2.onAuthenticationSuccess()
            val vault2 = VaultRepository(db2, session2)
            val svc2 = ExportImportService(vault2, "test")

            svc2.decodeForPreview(bytes, pin)
            val preset = svc2.importPreset(ImportScopeSpec.Developer)!!
            val preview = svc2.filterActiveImport(preset)
            assertEquals(0, preview.accounts)
            assertEquals(5, preview.developerSummary.total)

            svc2.confirmImport()
            val dest = vault2.buildDestinationSnapshot()
            assertEquals(0, dest.accounts.size)
            assertEquals(5, dest.developerEntries.size)
        } finally {
            db2.close()
        }
    }

    // ---- 27. package FULL → import selected TOTP ----

    @Test
    fun `import selected TOTP from a full package`() = runBlocking {
        seedFull()
        val full = service.encodeExport(ExportScopeSpec.FullVault, SelectedItemSet(), pin)
        val bytes = encodedBytes(service.decodeWithoutSession(full.bytes, pin).snapshot, "full")

        val db2 = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val session2 = SecureSessionStateMachine()
            session2.beginAuthentication()
            session2.onAuthenticationSuccess()
            val vault2 = VaultRepository(db2, session2)
            val svc2 = ExportImportService(vault2, "test")

            svc2.decodeForPreview(bytes, pin)
            val selection = SelectedItemSet(selectedTotpStableIds = setOf("totp-2"))
            val preview = svc2.filterActiveImport(selection)
            assertEquals(1, preview.totpCredentials)

            svc2.confirmImport()
            val dest = vault2.buildDestinationSnapshot()
            assertEquals(1, dest.accounts.size)
            assertEquals(listOf("totp-2"), dest.accounts.single().totpCredentials.map { it.stableId })
            assertTrue(dest.developerEntries.isEmpty())
        } finally {
            db2.close()
        }
    }

    // ---- 28. package FULL → import selected Recovery Set ----

    @Test
    fun `import selected recovery set preserves USED and usedAt`() = runBlocking {
        seedFull()
        val full = service.encodeExport(ExportScopeSpec.FullVault, SelectedItemSet(), pin)
        val bytes = encodedBytes(service.decodeWithoutSession(full.bytes, pin).snapshot, "full")

        val db2 = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val session2 = SecureSessionStateMachine()
            session2.beginAuthentication()
            session2.onAuthenticationSuccess()
            val vault2 = VaultRepository(db2, session2)
            val svc2 = ExportImportService(vault2, "test")

            svc2.decodeForPreview(bytes, pin)
            val selection = SelectedItemSet(selectedRecoverySetStableIds = setOf("set-1"))
            val preview = svc2.filterActiveImport(selection)
            assertEquals(1, preview.recoverySets)

            svc2.confirmImport()
            val dest = vault2.buildDestinationSnapshot()
            val set = dest.accounts.single().recoveryCodeSets.single()
            assertEquals(2, set.codes.size)
            assertEquals("USED", set.codes.first { it.stableId == "c-2" }.status)
            assertEquals("2024-02-02T00:00:00Z", set.codes.first { it.stableId == "c-2" }.usedAt)
        } finally {
            db2.close()
        }
    }

    // ---- 29. package FULL → import selected Developer ----

    @Test
    fun `import selected developer entry`() = runBlocking {
        seedFull()
        val full = service.encodeExport(ExportScopeSpec.FullVault, SelectedItemSet(), pin)
        val bytes = encodedBytes(service.decodeWithoutSession(full.bytes, pin).snapshot, "full")

        val db2 = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val session2 = SecureSessionStateMachine()
            session2.beginAuthentication()
            session2.onAuthenticationSuccess()
            val vault2 = VaultRepository(db2, session2)
            val svc2 = ExportImportService(vault2, "test")

            svc2.decodeForPreview(bytes, pin)
            val selection = SelectedItemSet(selectedDeveloperStableIds = setOf("dev-generic"))
            svc2.filterActiveImport(selection)

            svc2.confirmImport()
            val dest = vault2.buildDestinationSnapshot()
            assertEquals(1, dest.developerEntries.size)
            val generic = dest.developerEntries.single() as VaultGenericSecret
            assertEquals("t-1", generic.fields.first { it.key == "token" }.value)
            assertTrue(dest.accounts.isEmpty())
        } finally {
            db2.close()
        }
    }

    // ---- 30. selected package can only further narrow contents ----

    @Test
    fun `selected package can only be narrowed further on import`() = runBlocking {
        seedFull()
        // Export an authenticator-only package.
        val auth = service.encodeExport(ExportScopeSpec.Authenticator, SelectedItemSet(), pin)
        val authPayload = service.decodeWithoutSession(auth.bytes, pin)

        val db2 = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val session2 = SecureSessionStateMachine()
            session2.beginAuthentication()
            session2.onAuthenticationSuccess()
            val vault2 = VaultRepository(db2, session2)
            val svc2 = ExportImportService(vault2, "test")

            svc2.decodeForPreview(auth.bytes, pin)
            // The package contains no developer data → the Developer option must
            // be unavailable (its preset still works, but selecting a developer
            // stableId that isn't present fails).
            assertEquals(0, svc2.importPreset(ImportScopeSpec.Developer)!!.selectedDeveloperStableIds.size)

            // Narrow to one TOTP (subset of the authenticator-only package).
            val selection = SelectedItemSet(selectedTotpStableIds = setOf("totp-1"))
            val preview = svc2.filterActiveImport(selection)
            assertEquals(1, preview.totpCredentials)

            // Selecting a developer entry that is NOT in the package fails.
            try {
                svc2.filterActiveImport(SelectedItemSet(selectedDeveloperStableIds = setOf("dev-api")))
                fail("expected StaleSelectionException")
            } catch (e: StaleSelectionException) {
                // expected — cannot restore objects the package does not carry
            }
        } finally {
            db2.close()
        }
    }

    // ---- 31. unselected conflict ignored ----

    @Test
    fun `unselected conflict does not block selected import`() = runBlocking {
        seedFull()
        val full = service.encodeExport(ExportScopeSpec.FullVault, SelectedItemSet(), pin)
        val bytes = encodedBytes(service.decodeWithoutSession(full.bytes, pin).snapshot, "full")

        val db2 = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val session2 = SecureSessionStateMachine()
            session2.beginAuthentication()
            session2.onAuthenticationSuccess()
            val vault2 = VaultRepository(db2, session2)
            val svc2 = ExportImportService(vault2, "test")

            // Seed a conflicting destination: same account + TOTP stableId with a
            // DIFFERENT secret (would CONFLICT if imported).
            vault2.applySnapshot(
                MergeTestData.fullSnapshot(
                    MergeTestData.account(
                        "acc-1", "GitHub", "alice",
                        totps = listOf(MergeTestData.totp("totp-1", secret = "4F6VS6KX3UXWY2FQ")),
                    ),
                ),
                "seed-dest",
            )

            svc2.decodeForPreview(bytes, pin)
            // Select only the developer section — the conflicting TOTP is NOT
            // selected, so it cannot block the import.
            val selection = SelectedItemSet(selectedDeveloperStableIds = setOf("dev-api"))
            val preview = svc2.filterActiveImport(selection)
            assertEquals(0, preview.conflicts)
            assertTrue(!preview.blocked)

            val outcome = svc2.confirmImport()
            assertTrue(outcome is ImportOutcome.Applied)
        } finally {
            db2.close()
        }
    }

    // ---- 32. selected conflict blocked ----

    @Test
    fun `selected conflict blocks the import`() = runBlocking {
        seedFull()
        val full = service.encodeExport(ExportScopeSpec.FullVault, SelectedItemSet(), pin)
        val bytes = encodedBytes(service.decodeWithoutSession(full.bytes, pin).snapshot, "full")

        val db2 = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val session2 = SecureSessionStateMachine()
            session2.beginAuthentication()
            session2.onAuthenticationSuccess()
            val vault2 = VaultRepository(db2, session2)
            val svc2 = ExportImportService(vault2, "test")

            // Seed a conflict for the SELECTED TOTP.
            vault2.applySnapshot(
                MergeTestData.fullSnapshot(
                    MergeTestData.account(
                        "acc-1", "GitHub", "alice",
                        totps = listOf(MergeTestData.totp("totp-1", secret = "4F6VS6KX3UXWY2FQ")),
                    ),
                ),
                "seed-dest",
            )

            svc2.decodeForPreview(bytes, pin)
            val selection = SelectedItemSet(selectedTotpStableIds = setOf("totp-1"))
            val preview = svc2.filterActiveImport(selection)
            assertTrue(preview.conflicts > 0)
            assertTrue(preview.blocked)

            val outcome = svc2.confirmImport()
            assertTrue(outcome is ImportOutcome.Blocked)
            assertEquals(1, (outcome as ImportOutcome.Blocked).result.conflicts)
        } finally {
            db2.close()
        }
    }

    // ---- 33. Recovery divergence selected → blocked ----

    @Test
    fun `selected recovery divergence blocks`() = runBlocking {
        seedFull()
        val full = service.encodeExport(ExportScopeSpec.FullVault, SelectedItemSet(), pin)
        val bytes = encodedBytes(service.decodeWithoutSession(full.bytes, pin).snapshot, "full")

        val db2 = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val session2 = SecureSessionStateMachine()
            session2.beginAuthentication()
            session2.onAuthenticationSuccess()
            val vault2 = VaultRepository(db2, session2)
            val svc2 = ExportImportService(vault2, "test")

            // Destination has the same set with UNUSED (source has USED) →
            // state divergence.
            vault2.applySnapshot(
                MergeTestData.fullSnapshot(
                    MergeTestData.account(
                        "acc-1", "GitHub", "alice",
                        recoverySets = listOf(
                            MergeTestData.recoverySet(
                                "set-1",
                                codes = listOf(
                                    MergeTestData.recoveryCode("c-1", "111-111", status = "UNUSED"),
                                    MergeTestData.recoveryCode("c-2", "222-222", status = "UNUSED"),
                                ),
                            ),
                        ),
                    ),
                ),
                "seed-dest",
            )

            svc2.decodeForPreview(bytes, pin)
            val selection = SelectedItemSet(selectedRecoverySetStableIds = setOf("set-1"))
            val preview = svc2.filterActiveImport(selection)
            assertTrue(preview.stateDivergences > 0)
            assertTrue(preview.blocked)

            val outcome = svc2.confirmImport()
            assertTrue(outcome is ImportOutcome.Blocked)
            assertTrue((outcome as ImportOutcome.Blocked).result.stateDivergences > 0)
        } finally {
            db2.close()
        }
    }

    // ---- 34. final apply re-plan catches newly introduced conflict ----

    @Test
    fun `final apply re-plan catches a newly introduced conflict`() = runBlocking {
        seedFull()
        val full = service.encodeExport(ExportScopeSpec.FullVault, SelectedItemSet(), pin)
        val bytes = encodedBytes(service.decodeWithoutSession(full.bytes, pin).snapshot, "full")

        val db2 = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val session2 = SecureSessionStateMachine()
            session2.beginAuthentication()
            session2.onAuthenticationSuccess()
            val vault2 = VaultRepository(db2, session2)
            val svc2 = ExportImportService(vault2, "test")

            svc2.decodeForPreview(bytes, pin)
            val selection = SelectedItemSet(selectedTotpStableIds = setOf("totp-1"))
            val preview = svc2.filterActiveImport(selection)
            assertTrue(!preview.blocked)

            // Destination changes between preview and confirm: a conflict is
            // introduced AFTER the preview.
            vault2.applySnapshot(
                MergeTestData.fullSnapshot(
                    MergeTestData.account(
                        "acc-1", "GitHub", "alice",
                        totps = listOf(MergeTestData.totp("totp-1", secret = "4F6VS6KX3UXWY2FQ")),
                    ),
                ),
                "seed-other",
            )

            // Confirm re-plans against the live destination → BLOCK.
            val outcome = svc2.confirmImport()
            assertTrue(outcome is ImportOutcome.Blocked)
            assertEquals(1, (outcome as ImportOutcome.Blocked).result.conflicts)
        } finally {
            db2.close()
        }
    }

    // ---- 35. repeated selective import idempotent ----

    @Test
    fun `repeated selective import is idempotent`() = runBlocking {
        seedFull()
        val full = service.encodeExport(ExportScopeSpec.FullVault, SelectedItemSet(), pin)
        val bytes = encodedBytes(service.decodeWithoutSession(full.bytes, pin).snapshot, "full")

        val db2 = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val session2 = SecureSessionStateMachine()
            session2.beginAuthentication()
            session2.onAuthenticationSuccess()
            val vault2 = VaultRepository(db2, session2)
            val svc2 = ExportImportService(vault2, "test")

            val selection = SelectedItemSet(selectedTotpStableIds = setOf("totp-1"))
            svc2.decodeForPreview(bytes, pin)
            svc2.filterActiveImport(selection)
            val first = svc2.confirmImport() as ImportOutcome.Applied
            assertEquals(1, first.result.insertedTotp)

            svc2.decodeForPreview(bytes, pin)
            svc2.filterActiveImport(selection)
            val second = svc2.confirmImport() as ImportOutcome.Applied
            assertEquals(0, second.result.insertedTotp)
            assertEquals(1, second.result.duplicates)
            assertEquals(1, vault2.buildDestinationSnapshot().accounts.single().totpCredentials.size)
        } finally {
            db2.close()
        }
    }

    // ---- 36. ImportRecord only after success ----

    @Test
    fun `import record only after success`() = runBlocking {
        seedFull()
        val full = service.encodeExport(ExportScopeSpec.FullVault, SelectedItemSet(), pin)
        val bytes = encodedBytes(service.decodeWithoutSession(full.bytes, pin).snapshot, "full")

        val db2 = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val session2 = SecureSessionStateMachine()
            session2.beginAuthentication()
            session2.onAuthenticationSuccess()
            val vault2 = VaultRepository(db2, session2)
            val svc2 = ExportImportService(vault2, "test")

            // Seed a destination (this seeds an ImportRecord too — record count
            // is the baseline).
            vault2.applySnapshot(
                MergeTestData.fullSnapshot(
                    MergeTestData.account(
                        "acc-1", "GitHub", "alice",
                        totps = listOf(MergeTestData.totp("totp-1", secret = "4F6VS6KX3UXWY2FQ")),
                    ),
                ),
                "seed-dest",
            )
            val recordsBefore = db2.importRecordDao().listAll().size

            // Blocked selective import → no NEW ImportRecord.
            svc2.decodeForPreview(bytes, pin)
            svc2.filterActiveImport(SelectedItemSet(selectedTotpStableIds = setOf("totp-1")))
            val blocked = svc2.confirmImport()
            assertTrue(blocked is ImportOutcome.Blocked)
            assertEquals(recordsBefore, db2.importRecordDao().listAll().size)

            // Successful selective import → exactly one NEW ImportRecord.
            val okSelection = SelectedItemSet(selectedTotpStableIds = setOf("totp-2"))
            svc2.decodeForPreview(bytes, pin)
            svc2.filterActiveImport(okSelection)
            val applied = svc2.confirmImport()
            assertTrue(applied is ImportOutcome.Applied)
            assertEquals(recordsBefore + 1, db2.importRecordDao().listAll().size)
        } finally {
            db2.close()
        }
    }

    // ---- 37. cancel clears decoded plaintext ----

    @Test
    fun `cancel clears decoded plaintext`() = runBlocking {
        seedFull()
        val full = service.encodeExport(ExportScopeSpec.FullVault, SelectedItemSet(), pin)
        val bytes = encodedBytes(service.decodeWithoutSession(full.bytes, pin).snapshot, "full")

        service.decodeForPreview(bytes, pin)
        assertTrue(service.activeImportSession() != null)
        service.clearImportSession()
        assertNull(service.activeImportSession())
    }

    // ---- stale import selection fails safely (narrowing contract) ----

    @Test
    fun `import selection of a stale stableId fails safely`() = runBlocking {
        seedFull()
        val full = service.encodeExport(ExportScopeSpec.FullVault, SelectedItemSet(), pin)
        val bytes = encodedBytes(service.decodeWithoutSession(full.bytes, pin).snapshot, "full")

        service.decodeForPreview(bytes, pin)
        try {
            service.filterActiveImport(SelectedItemSet(selectedTotpStableIds = setOf("totp-GONE")))
            fail("expected StaleSelectionException")
        } catch (e: StaleSelectionException) {
            // expected
        }
        try {
            service.filterActiveImport(SelectedItemSet())
            fail("expected EmptySelectionException")
        } catch (e: EmptySelectionException) {
            // expected
        }
    }
}
