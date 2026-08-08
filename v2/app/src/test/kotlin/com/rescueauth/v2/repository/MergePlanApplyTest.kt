package com.rescueauth.v2.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultAndroidSigningKey
import com.rescueauth.v2.export.VaultApiCredential
import com.rescueauth.v2.export.VaultEnvironmentVariableSet
import com.rescueauth.v2.export.VaultGenericSecret
import com.rescueauth.v2.export.VaultKeyValue
import com.rescueauth.v2.export.VaultPackagePayload
import com.rescueauth.v2.export.VaultSnapshot
import com.rescueauth.v2.export.VaultSshKey
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 3C transactional import/merge apply tests on an in-memory Room DB
 * (SQLCipher path is covered by the instrumented suite; all Phase 3C logic is
 * DB-backend agnostic).
 *
 * Covers: Developer persistence round-trips, basic apply (empty destination /
 * Authenticator-only / Developer-only / selected-items), parent-child identity
 * resolution, merge behavior (INSERT / DUPLICATE / CONFLICT / destination-only
 * preserved / Developer stableId semantics), Recovery used/unused divergence,
 * idempotence, transactionality (failure injection via [WriteSeam]) and
 * close/reopen persistence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MergePlanApplyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = SecureSessionStateMachine()
        session.beginAuthentication()
        session.onAuthenticationSuccess()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun repo() = VaultRepository(db, session)

    private fun payload(snapshot: VaultSnapshot, packageId: String = "pkg-test") = VaultPackagePayload(
        logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
        packageId = packageId,
        createdAt = "2024-01-01T00:00:00Z",
        snapshot = snapshot,
    )

    // ------------------------------------------------------------------
    // Developer persistence
    // ------------------------------------------------------------------

    @Test
    fun `all five developer types insert and read back round-trip`() = runBlocking {
        val outcome = repo().applyMergePlan(payload(MergeTestData.fullSnapshot(developers = MergeTestData.allFiveDevelopers())))
        assertTrue(outcome is ImportOutcome.Applied)
        assertEquals(5, (outcome as ImportOutcome.Applied).result.insertedDeveloperEntries)

        val rows = db.developerEntryDao().listAll()
        assertEquals(5, rows.size)
        assertEquals(setOf("ANDROID_SIGNING_KEY", "API_CREDENTIAL", "SSH_KEY", "ENVIRONMENT_VARIABLE_SET", "GENERIC_SECRET"),
            rows.map { it.entryType }.toSet())

        // Round-trip back to logical preserves every field.
        val logical = rows.map { DeveloperMappers.toLogical(it) }
        val back = MergeTestData.allFiveDevelopers()
        assertEquals(back.map { it.stableId }.sorted(), logical.map { it.stableId }.sorted())
        assertEquals(back.size, logical.size)
    }

    @Test
    fun `android signing key binary keystore bytes exact round-trip`() = runBlocking {
        val keystoreBytes = ByteArray(64) { it.toByte() }
        val keystoreB64 = java.util.Base64.getEncoder().encodeToString(keystoreBytes)
        val entry = MergeTestData.signingKey("sk-1", keystoreBase64 = keystoreB64)
        repo().applyMergePlan(payload(MergeTestData.fullSnapshot(developers = listOf(entry))))

        val stored = db.developerEntryDao().listAll().single()
        val back = DeveloperMappers.toLogical(stored) as VaultAndroidSigningKey
        assertEquals(keystoreB64, back.keystoreBase64)
        assertEquals(keystoreBytes.toList(), java.util.Base64.getDecoder().decode(back.keystoreBase64).toList())
    }

    @Test
    fun `password private key and value fields round-trip exactly`() = runBlocking {
        val developers = listOf(
            MergeTestData.signingKey("sk-1", storePassword = "s3cr3t-store", keyPassword = "s3cr3t-key", keystoreBase64 = "AAECAwQFBgc="),
            MergeTestData.apiCredential("api-1", apiKey = "ak-123", apiSecret = "as-456"),
            MergeTestData.sshKey("ssh-1", privateKey = "PRIV-KEY-LINE", passphrase = "pass-1"),
            MergeTestData.envVarSet("env-1", variables = listOf(VaultKeyValue("TOKEN", "v-1"), VaultKeyValue("SECRET", "v-2"))),
            MergeTestData.genericSecret("gen-1", fields = listOf(VaultKeyValue("password", "p-1"))),
        )
        repo().applyMergePlan(payload(MergeTestData.fullSnapshot(developers = developers)))

        val back = db.developerEntryDao().listAll().map { DeveloperMappers.toLogical(it) }
        val signing = back.filterIsInstance<VaultAndroidSigningKey>().single()
        assertEquals("s3cr3t-store", signing.storePassword)
        assertEquals("s3cr3t-key", signing.keyPassword)
        val api = back.filterIsInstance<VaultApiCredential>().single()
        assertEquals("ak-123", api.apiKey)
        assertEquals("as-456", api.apiSecret)
        val ssh = back.filterIsInstance<VaultSshKey>().single()
        assertEquals("PRIV-KEY-LINE", ssh.privateKey)
        assertEquals("pass-1", ssh.passphrase)
        val env = back.filterIsInstance<VaultEnvironmentVariableSet>().single()
        assertEquals(listOf(VaultKeyValue("TOKEN", "v-1"), VaultKeyValue("SECRET", "v-2")), env.variables)
        val gen = back.filterIsInstance<VaultGenericSecret>().single()
        assertEquals(listOf(VaultKeyValue("password", "p-1")), gen.fields)
    }

    // ------------------------------------------------------------------
    // Basic apply
    // ------------------------------------------------------------------

    @Test
    fun `empty destination plus full snapshot inserts everything`() = runBlocking {
        val snapshot = MergeTestData.fullSnapshot(
            MergeTestData.account("acc-1", "GitHub", "alice",
                totps = listOf(MergeTestData.totp("totp-1")),
                recoverySets = listOf(MergeTestData.recoverySet("set-1", codes = listOf(MergeTestData.recoveryCode("c1", "AAAA")))),
            ),
            developers = listOf(MergeTestData.genericSecret("g1")),
        )
        val outcome = repo().applyMergePlan(payload(snapshot))
        val applied = outcome as ImportOutcome.Applied
        assertEquals(1, applied.result.insertedAccounts)
        assertEquals(1, applied.result.insertedTotp)
        assertEquals(1, applied.result.insertedRecoverySets)
        assertEquals(1, applied.result.insertedRecoveryCodes)
        assertEquals(1, applied.result.insertedDeveloperEntries)

        assertEquals(1, db.authAccountDao().count())
        assertEquals(1, db.totpCredentialDao().listAll().size)
        assertEquals(1, db.recoveryCodeDao().listBySet("set-1").size)
        assertEquals(1, db.developerEntryDao().count())
        // ImportRecord written only on success.
        assertEquals(1, db.importRecordDao().listAll().size)
    }

    @Test
    fun `authenticator-only apply`() = runBlocking {
        val snapshot = VaultSnapshot(
            scope = SnapshotScope.AUTHENTICATOR_ONLY,
            accounts = listOf(MergeTestData.account("acc-1", "GitHub", "alice", totps = listOf(MergeTestData.totp("t1")))),
        )
        val applied = repo().applyMergePlan(payload(snapshot)) as ImportOutcome.Applied
        assertEquals(1, applied.result.insertedAccounts)
        assertEquals(1, applied.result.insertedTotp)
        assertEquals(0, db.developerEntryDao().count())
    }

    @Test
    fun `developer-only apply`() = runBlocking {
        val snapshot = VaultSnapshot(
            scope = SnapshotScope.DEVELOPER_ONLY,
            developerEntries = listOf(MergeTestData.apiCredential("api-1")),
        )
        val applied = repo().applyMergePlan(payload(snapshot)) as ImportOutcome.Applied
        assertEquals(1, applied.result.insertedDeveloperEntries)
        assertEquals(0, db.authAccountDao().count())
        assertEquals(0, db.totpCredentialDao().listAll().size)
    }

    @Test
    fun `selected-items apply`() = runBlocking {
        val snapshot = VaultSnapshot(
            scope = SnapshotScope.SELECTED_ITEMS,
            accounts = listOf(
                MergeTestData.account("acc-1", "GitHub", "alice", totps = listOf(MergeTestData.totp("t1"))),
                MergeTestData.account("acc-2", "Google", "bob"),
            ),
            developerEntries = listOf(MergeTestData.sshKey("ssh-1")),
        )
        val applied = repo().applyMergePlan(payload(snapshot)) as ImportOutcome.Applied
        // acc-2 has no children to insert -> the planner does not create it
        // (an account without inserts is an empty duplicate). Only acc-1 is
        // inserted.
        assertEquals(1, applied.result.insertedAccounts)
        assertEquals(1, applied.result.insertedTotp)
        assertEquals(1, applied.result.insertedDeveloperEntries)
    }

    // ------------------------------------------------------------------
    // Parent resolution
    // ------------------------------------------------------------------

    @Test
    fun `duplicate provider with new account does not create duplicate parent`() = runBlocking {
        val r = repo()
        // Seed destination with GitHub/alice carrying an existing TOTP (an
        // account with no children is never inserted by the planner).
        r.applyMergePlan(payload(MergeTestData.fullSnapshot(
            MergeTestData.account("dest-acc", "GitHub", "alice",
                totps = listOf(MergeTestData.totp("dest-totp", secret = "4F6VS6KX3UXWY2FQ"))),
        )))
        assertEquals(1, db.authAccountDao().count())

        // Source has a DIFFERENT stableId for the same account label and a new TOTP.
        val source = MergeTestData.fullSnapshot(
            MergeTestData.account("src-acc", "GitHub", "alice", totps = listOf(MergeTestData.totp("totp-new"))),
        )
        val applied = r.applyMergePlan(payload(source, "pkg-2")) as ImportOutcome.Applied

        // Still exactly ONE account (no duplicate provider/account).
        assertEquals(1, db.authAccountDao().count())
        // The source account is a semantic duplicate -> USE_EXISTING (no new
        // account row), only the new TOTP is inserted.
        assertEquals(0, applied.result.insertedAccounts)
        assertEquals(1, applied.result.insertedTotp)

        // The new TOTP is attached to the EXISTING destination account.
        val newTotp = db.totpCredentialDao().listAll().first { it.stableId == "totp-new" }
        assertEquals("dest-acc", newTotp.accountId)
        // Mapping: source src-acc -> destination dest-acc.
        assertEquals("dest-acc", applied.result.resolvedAccount["src-acc"])
        assertEquals("dest-acc", applied.result.resolvedProvider["src-acc"])
    }

    @Test
    fun `duplicate account with new totp attaches child to correct destination`() = runBlocking {
        val r = repo()
        // Destination account "acc-1" (GitHub/alice) with an existing totp.
        r.applyMergePlan(payload(MergeTestData.fullSnapshot(
            MergeTestData.account("acc-1", "GitHub", "alice", totps = listOf(MergeTestData.totp("existing"))),
        )))

        // Source: same stableId acc-1, same existing totp (duplicate) + one new totp.
        val source = MergeTestData.fullSnapshot(
            MergeTestData.account("acc-1", "GitHub", "alice",
                totps = listOf(MergeTestData.totp("existing"), MergeTestData.totp("new-totp", secret = "4F6VS6KX3UXWY2FQ"))),
        )
        val applied = r.applyMergePlan(payload(source, "pkg-2")) as ImportOutcome.Applied

        assertEquals(1, applied.result.insertedTotp)
        assertEquals(1, applied.result.duplicates)
        // Still one account; new TOTP under acc-1.
        assertEquals(1, db.authAccountDao().count())
        val newTotp = db.totpCredentialDao().listByAccount("acc-1").first { it.stableId == "new-totp" }
        assertNotNull(newTotp)
    }

    @Test
    fun `semantic account dedupe child points to correct destination`() = runBlocking {
        val r = repo()
        // Destination created with a different stableId than the source.
        r.applyMergePlan(payload(MergeTestData.fullSnapshot(
            MergeTestData.account("dest-provider", "GitHub", "alice", totps = listOf(MergeTestData.totp("dest-totp"))),
        )))

        // Source account has a new stableId + a new recovery set to insert.
        val source = MergeTestData.fullSnapshot(
            MergeTestData.account("src-provider", "GitHub", "alice",
                recoverySets = listOf(
                    MergeTestData.recoverySet("src-set", codes = listOf(MergeTestData.recoveryCode("src-c1", "AAAA-BBBB"))),
                ),
            ),
        )
        val applied = r.applyMergePlan(payload(source, "pkg-2")) as ImportOutcome.Applied

        // No duplicate account created; the source account mapped onto the
        // existing destination (semantic dedupe).
        assertEquals(1, db.authAccountDao().count())
        assertEquals(0, applied.result.insertedAccounts)
        // The recovery set attaches to the destination account Room id.
        val set = db.recoveryCodeSetDao().listByAccount("dest-provider").single()
        assertEquals("src-set", set.stableId)
        assertEquals("dest-provider", set.accountId)
        assertEquals("dest-provider", applied.result.resolvedAccount["src-provider"])
        assertEquals("dest-provider", applied.result.resolvedProvider["src-provider"])
    }

    @Test
    fun `inserted account uses its own stableId as parent`() = runBlocking {
        val r = repo()
        val source = MergeTestData.fullSnapshot(
            MergeTestData.account("brand-new", "NewCo", "carol", totps = listOf(MergeTestData.totp("t1"))),
        )
        val applied = r.applyMergePlan(payload(source)) as ImportOutcome.Applied
        val acc = db.authAccountDao().listAll().single()
        assertEquals("brand-new", acc.id)
        assertEquals("brand-new", acc.stableId)
        val totp = db.totpCredentialDao().listAll().single()
        assertEquals("brand-new", totp.accountId)
        assertEquals("brand-new", applied.result.resolvedAccount["brand-new"])
    }

    // ------------------------------------------------------------------
    // Merge behavior
    // ------------------------------------------------------------------

    @Test
    fun `insert applied duplicate no-op`() = runBlocking {
        val r = repo()
        val snap = MergeTestData.fullSnapshot(MergeTestData.account("a1", "GitHub", "alice", totps = listOf(MergeTestData.totp("t1"))))
        r.applyMergePlan(payload(snap))
        val second = r.applyMergePlan(payload(snap, "pkg-2")) as ImportOutcome.Applied
        assertEquals(0, second.result.insertedTotp)
        assertEquals(1, second.result.duplicates)
        assertEquals(1, db.totpCredentialDao().listAll().size)
        assertEquals(1, db.authAccountDao().count())
    }

    @Test
    fun `conflict blocks apply and writes nothing`() = runBlocking {
        val r = repo()
        // Destination has totp-1 with secret A.
        r.applyMergePlan(payload(MergeTestData.fullSnapshot(
            MergeTestData.account("a1", "GitHub", "alice", totps = listOf(MergeTestData.totp("totp-1", secret = "JBSWY3DPEHPK3PXP"))),
        )))
        assertEquals(1, db.importRecordDao().listAll().size)

        // Source same stableId but different secret -> CONFLICT -> blocked.
        val source = MergeTestData.fullSnapshot(
            MergeTestData.account("a1", "GitHub", "alice", totps = listOf(MergeTestData.totp("totp-1", secret = "4F6VS6KX3UXWY2FQ"))),
        )
        val outcome = r.applyMergePlan(payload(source, "pkg-2"))
        assertTrue(outcome is ImportOutcome.Blocked)
        assertEquals(1, (outcome as ImportOutcome.Blocked).result.conflicts)

        // Nothing was written: still 1 account, 1 totp, 1 import record.
        assertEquals(1, db.authAccountDao().count())
        assertEquals(1, db.totpCredentialDao().listAll().size)
        assertEquals(1, db.importRecordDao().listAll().size)
        assertEquals("JBSWY3DPEHPK3PXP", db.totpCredentialDao().listAll().single().secretBase32)
    }

    @Test
    fun `destination-only records are never deleted`() = runBlocking {
        val r = repo()
        val dest = MergeTestData.fullSnapshot(
            MergeTestData.account("a1", "GitHub", "alice", totps = listOf(MergeTestData.totp("t1"))),
            MergeTestData.account("a2", "Google", "bob", totps = listOf(MergeTestData.totp("t2"))),
        )
        r.applyMergePlan(payload(dest))

        // Source only carries a1.
        val source = MergeTestData.fullSnapshot(MergeTestData.account("a1", "GitHub", "alice", totps = listOf(MergeTestData.totp("t1"))))
        val applied = r.applyMergePlan(payload(source, "pkg-2")) as ImportOutcome.Applied
        // a2's totp remains (unchanged).
        assertEquals(2, db.authAccountDao().count())
        assertEquals(2, db.totpCredentialDao().listAll().size)
        assertTrue(applied.result.unchanged >= 1)
    }

    @Test
    fun `developer different stableId keeps both`() = runBlocking {
        val r = repo()
        r.applyMergePlan(payload(MergeTestData.fullSnapshot(developers = listOf(MergeTestData.apiCredential("api-1", apiKey = "sk_1")))))
        // Different stableId, identical payload -> INSERT / keep both.
        val second = r.applyMergePlan(payload(MergeTestData.fullSnapshot(developers = listOf(MergeTestData.apiCredential("api-2", apiKey = "sk_1"))), "pkg-2")) as ImportOutcome.Applied
        assertEquals(1, second.result.insertedDeveloperEntries)
        assertEquals(2, db.developerEntryDao().count())
    }

    @Test
    fun `developer same stableId identical is duplicate no-op`() = runBlocking {
        val r = repo()
        val entry = MergeTestData.sshKey("k1")
        r.applyMergePlan(payload(MergeTestData.fullSnapshot(developers = listOf(entry))))
        val second = r.applyMergePlan(payload(MergeTestData.fullSnapshot(developers = listOf(entry)), "pkg-2")) as ImportOutcome.Applied
        assertEquals(0, second.result.insertedDeveloperEntries)
        assertEquals(1, second.result.duplicates)
        assertEquals(1, db.developerEntryDao().count())
    }

    @Test
    fun `developer same stableId changed is conflict blocks apply`() = runBlocking {
        val r = repo()
        r.applyMergePlan(payload(MergeTestData.fullSnapshot(developers = listOf(MergeTestData.genericSecret("g1", fields = listOf(VaultKeyValue("token", "t-1")))))))
        // Same stableId, changed field value -> CONFLICT.
        val source = MergeTestData.fullSnapshot(developers = listOf(MergeTestData.genericSecret("g1", fields = listOf(VaultKeyValue("token", "t-2")))))
        val outcome = r.applyMergePlan(payload(source, "pkg-2"))
        assertTrue(outcome is ImportOutcome.Blocked)
        assertEquals(1, (outcome as ImportOutcome.Blocked).result.conflicts)
        assertEquals(1, db.developerEntryDao().count())
        // Nothing new written.
        assertEquals(1, db.importRecordDao().listAll().size)
    }

    // ------------------------------------------------------------------
    // Recovery used/unused divergence
    // ------------------------------------------------------------------

    @Test
    fun `recovery used-unused divergence blocks apply not silently lost`() = runBlocking {
        val r = repo()
        // Destination set with code c1 UNUSED.
        r.applyMergePlan(payload(MergeTestData.fullSnapshot(
            MergeTestData.account("a1", "GitHub", "alice",
                recoverySets = listOf(MergeTestData.recoverySet("set-1", codes = listOf(MergeTestData.recoveryCode("c1", "AAAA-BBBB", status = "UNUSED"))))),
        )))

        // Source same set but code USED -> divergence.
        val source = MergeTestData.fullSnapshot(
            MergeTestData.account("a1", "GitHub", "alice",
                recoverySets = listOf(MergeTestData.recoverySet("set-1", codes = listOf(MergeTestData.recoveryCode("c1", "AAAA-BBBB", status = "USED", usedAt = "2024-05-01T00:00:00Z"))))),
        )
        val outcome = r.applyMergePlan(payload(source, "pkg-2"))
        assertTrue(outcome is ImportOutcome.Blocked)
        assertEquals(1, (outcome as ImportOutcome.Blocked).result.stateDivergences)

        // Destination state untouched (still UNUSED).
        assertEquals("UNUSED", db.recoveryCodeDao().listBySet("set-1").single().status)
        assertEquals(1, db.importRecordDao().listAll().size)
    }

    @Test
    fun `recovery identical status is duplicate not divergence`() = runBlocking {
        val r = repo()
        val snap = MergeTestData.fullSnapshot(
            MergeTestData.account("a1", "GitHub", "alice",
                recoverySets = listOf(MergeTestData.recoverySet("set-1", codes = listOf(MergeTestData.recoveryCode("c1", "AAAA-BBBB", status = "USED", usedAt = "2024-05-01T00:00:00Z"))))),
        )
        r.applyMergePlan(payload(snap))
        val second = r.applyMergePlan(payload(snap, "pkg-2")) as ImportOutcome.Applied
        assertEquals(0, second.result.insertedRecoverySets)
        assertEquals(0, second.result.stateDivergences)
        assertEquals(1, second.result.duplicates)
    }

    // ------------------------------------------------------------------
    // Idempotence
    // ------------------------------------------------------------------

    @Test
    fun `same package applied twice is idempotent`() = runBlocking {
        val r = repo()
        val snap = MergeTestData.fullSnapshot(
            MergeTestData.account("a1", "GitHub", "alice",
                totps = listOf(MergeTestData.totp("t1")),
                recoverySets = listOf(MergeTestData.recoverySet("set-1", codes = listOf(MergeTestData.recoveryCode("c1", "AAAA")))),
            ),
            developers = MergeTestData.allFiveDevelopers(),
        )
        val pkg = payload(snap)
        val first = r.applyMergePlan(pkg) as ImportOutcome.Applied
        assertTrue(first.result.insertedTotal > 0)

        val second = r.applyMergePlan(pkg) as ImportOutcome.Applied
        assertEquals(0, second.result.insertedTotal)
        assertTrue(second.result.duplicates > 0)

        assertEquals(1, db.authAccountDao().count())
        assertEquals(1, db.totpCredentialDao().listAll().size)
        assertEquals(1, db.recoveryCodeSetDao().listByAccount("a1").size)
        assertEquals(5, db.developerEntryDao().count())
        // Two successful import records (one per successful apply).
        assertEquals(2, db.importRecordDao().listAll().size)
    }

    @Test
    fun `mixed vault applied twice is idempotent`() = runBlocking {
        val r = repo()
        val snap = MergeTestData.fullSnapshot(
            MergeTestData.account("a1", "GitHub", "alice", totps = listOf(MergeTestData.totp("t1"))),
            MergeTestData.account("a2", "Google", "bob", totps = listOf(MergeTestData.totp("t2"))),
            developers = listOf(MergeTestData.apiCredential("api-1"), MergeTestData.sshKey("ssh-1")),
        )
        r.applyMergePlan(payload(snap))
        val second = r.applyMergePlan(payload(snap, "pkg-2")) as ImportOutcome.Applied
        assertEquals(0, second.result.insertedTotal)
        assertEquals(2, db.authAccountDao().count())
        assertEquals(2, db.totpCredentialDao().listAll().size)
        assertEquals(2, db.developerEntryDao().count())
    }

    @Test
    fun `developer entries applied twice are idempotent`() = runBlocking {
        val r = repo()
        val snap = MergeTestData.fullSnapshot(developers = MergeTestData.allFiveDevelopers())
        r.applyMergePlan(payload(snap))
        val second = r.applyMergePlan(payload(snap, "pkg-2")) as ImportOutcome.Applied
        assertEquals(0, second.result.insertedDeveloperEntries)
        assertEquals(5, second.result.duplicates)
        assertEquals(5, db.developerEntryDao().count())
    }

    @Test
    fun `parent semantic dedupe child insert idempotent on second import`() = runBlocking {
        val r = repo()
        val source = MergeTestData.fullSnapshot(
            MergeTestData.account("src-acc", "GitHub", "alice", totps = listOf(MergeTestData.totp("new-totp"))),
        )
        // First import: creates account? No — source account label matches nothing,
        // so INSERT_ACCOUNT + INSERT totp.
        val first = r.applyMergePlan(payload(source)) as ImportOutcome.Applied
        assertEquals(1, first.result.insertedAccounts)
        assertEquals(1, first.result.insertedTotp)

        // Second import of the same source: all duplicates.
        val second = r.applyMergePlan(payload(source, "pkg-2")) as ImportOutcome.Applied
        assertEquals(0, second.result.insertedAccounts)
        assertEquals(0, second.result.insertedTotp)
        assertEquals(1, db.authAccountDao().count())
        assertEquals(1, db.totpCredentialDao().listAll().size)
    }

    // ------------------------------------------------------------------
    // Transactionality / failure injection
    // ------------------------------------------------------------------

    private class FailingSeam : WriteSeam {
        override fun beforeDeveloperInsert() {
            throw RuntimeException("simulated developer insert failure")
        }
    }

    @Test
    fun `failure halfway rolls back everything`() = runBlocking {
        val r = repo()
        val snap = MergeTestData.fullSnapshot(
            MergeTestData.account("a1", "GitHub", "alice",
                totps = listOf(MergeTestData.totp("t1")),
                recoverySets = listOf(MergeTestData.recoverySet("set-1", codes = listOf(MergeTestData.recoveryCode("c1", "AAAA")))),
            ),
            developers = listOf(MergeTestData.apiCredential("api-1")),
        )
        try {
            r.applyMergePlan(payload(snap), seam = FailingSeam())
            assertTrue("expected simulated failure", false)
        } catch (e: RuntimeException) {
            // expected
        }
        // Everything rolled back: DB identical to pre-import state.
        assertEquals(0, db.authAccountDao().count())
        assertEquals(0, db.totpCredentialDao().listAll().size)
        assertEquals(0, db.recoveryCodeSetDao().listByAccount("a1").size)
        assertEquals(0, db.developerEntryDao().count())
        assertEquals(0, db.importRecordDao().listAll().size)
    }

    @Test
    fun `authenticator success then developer failure rolls back authenticator changes`() = runBlocking {
        val r = repo()
        val snap = MergeTestData.fullSnapshot(
            MergeTestData.account("a1", "GitHub", "alice", totps = listOf(MergeTestData.totp("t1"))),
            developers = listOf(MergeTestData.apiCredential("api-1")),
        )
        try {
            r.applyMergePlan(payload(snap), seam = FailingSeam())
            assertTrue("expected simulated failure", false)
        } catch (e: RuntimeException) {
            // expected
        }
        assertEquals(0, db.authAccountDao().count())
        assertEquals(0, db.totpCredentialDao().listAll().size)
        assertEquals(0, db.importRecordDao().listAll().size)
    }

    @Test
    fun `blocked plan writes no import record and no data`() = runBlocking {
        val r = repo()
        // Seed destination.
        r.applyMergePlan(payload(MergeTestData.fullSnapshot(
            MergeTestData.account("a1", "GitHub", "alice", totps = listOf(MergeTestData.totp("t1", secret = "JBSWY3DPEHPK3PXP"))),
        )))
        val before = db.importRecordDao().listAll().size

        val conflictSource = MergeTestData.fullSnapshot(
            MergeTestData.account("a1", "GitHub", "alice", totps = listOf(MergeTestData.totp("t1", secret = "4F6VS6KX3UXWY2FQ"))),
        )
        val outcome = r.applyMergePlan(payload(conflictSource, "pkg-2"))
        assertTrue(outcome is ImportOutcome.Blocked)
        assertEquals(before, db.importRecordDao().listAll().size)
    }

    // ------------------------------------------------------------------
    // Concurrency (repository serialization + transaction)
    // ------------------------------------------------------------------

    @Test
    fun `two concurrent imports do not produce partial or duplicate state`() = runBlocking {
        val r = repo()
        val snapA = MergeTestData.fullSnapshot(
            MergeTestData.account("a1", "GitHub", "alice", totps = listOf(MergeTestData.totp("t1"))),
        )
        val snapB = MergeTestData.fullSnapshot(
            MergeTestData.account("b1", "Google", "bob", totps = listOf(MergeTestData.totp("t2", secret = "4F6VS6KX3UXWY2FQ"))),
        )
        coroutineScope {
            val d1 = async { r.applyMergePlan(payload(snapA, "pkg-a")) }
            val d2 = async { r.applyMergePlan(payload(snapB, "pkg-b")) }
            awaitAll(d1, d2)
        }
        // Both imports committed (serialized) without any unique-constraint
        // violation or half-written state.
        assertEquals(2, db.authAccountDao().count())
        assertEquals(2, db.totpCredentialDao().listAll().size)
        assertEquals(2, db.importRecordDao().listAll().size)
    }

    // ------------------------------------------------------------------
    // Shared boundary (applySnapshot) — legacy / future adapters funnel here
    // ------------------------------------------------------------------

    @Test
    fun `applySnapshot funnels an already-validated snapshot through the same transactional path`() = runBlocking {
        val r = repo()
        val snapshot = MergeTestData.fullSnapshot(
            MergeTestData.account("a1", "GitHub", "alice", totps = listOf(MergeTestData.totp("t1"))),
            developers = listOf(MergeTestData.genericSecret("g1", fields = listOf(VaultKeyValue("x", "y")))),
        )
        val outcome = r.applySnapshot(snapshot, packageIdentity = "legacy-adapter-1")
        assertTrue(outcome is ImportOutcome.Applied)
        assertEquals(1, db.authAccountDao().count())
        assertEquals(1, db.totpCredentialDao().listAll().size)
        assertEquals(1, db.developerEntryDao().count())
        val record = db.importRecordDao().listAll().single()
        assertEquals("V2_PACKAGE", record.sourceType)
        assertEquals("legacy-adapter-1", record.sourceFingerprint)
        // No plaintext secret in the import record.
        assertTrue(!record.sourceFingerprint.contains("JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun `destination used source unused recovery divergence also blocks`() = runBlocking {
        val r = repo()
        r.applyMergePlan(payload(MergeTestData.fullSnapshot(
            MergeTestData.account("a1", "GitHub", "alice",
                recoverySets = listOf(MergeTestData.recoverySet("set-1", codes = listOf(MergeTestData.recoveryCode("c1", "AAAA-BBBB", status = "USED", usedAt = "2024-05-01"))))),
        )))
        val source = MergeTestData.fullSnapshot(
            MergeTestData.account("a1", "GitHub", "alice",
                recoverySets = listOf(MergeTestData.recoverySet("set-1", codes = listOf(MergeTestData.recoveryCode("c1", "AAAA-BBBB", status = "UNUSED"))))),
        )
        val outcome = r.applyMergePlan(payload(source, "pkg-2"))
        assertTrue(outcome is ImportOutcome.Blocked)
        assertEquals(1, (outcome as ImportOutcome.Blocked).result.stateDivergences)
        // Destination stays USED; source UNUSED state is not silently applied.
        assertEquals("USED", db.recoveryCodeDao().listBySet("set-1").single().status)
    }

    @Test
    fun `import record is written only on success`() = runBlocking {
        val r = repo()
        // Blocked import (conflict) writes no record.
        r.applyMergePlan(payload(MergeTestData.fullSnapshot(
            MergeTestData.account("a1", "GitHub", "alice", totps = listOf(MergeTestData.totp("t1", secret = "JBSWY3DPEHPK3PXP"))),
        )))
        assertEquals(1, db.importRecordDao().listAll().size)

        val conflictSource = MergeTestData.fullSnapshot(
            MergeTestData.account("a1", "GitHub", "alice", totps = listOf(MergeTestData.totp("t1", secret = "4F6VS6KX3UXWY2FQ"))),
        )
        val outcome = r.applyMergePlan(payload(conflictSource, "pkg-2"))
        assertTrue(outcome is ImportOutcome.Blocked)
        assertEquals(1, db.importRecordDao().listAll().size)

        // A failed mid-apply also rolls back the record.
        try {
            r.applyMergePlan(
                payload(MergeTestData.fullSnapshot(
                    MergeTestData.account("a2", "Google", "bob", totps = listOf(MergeTestData.totp("t2"))),
                    developers = listOf(MergeTestData.apiCredential("api-1")),
                ), "pkg-3"),
                seam = FailingSeam(),
            )
            assertTrue("expected simulated failure", false)
        } catch (e: RuntimeException) {
            // expected
        }
        assertEquals(1, db.importRecordDao().listAll().size)
    }

    // ------------------------------------------------------------------
    // Native / Legacy isolation
    // ------------------------------------------------------------------

    @Test
    fun `merge apply boundary never imports legacy types`() {
        val applyFile = java.io.File(
            "src/main/kotlin/com/rescueauth/v2/repository/MergePlanApplicator.kt",
        )
        assertTrue(applyFile.isFile)
        val content = applyFile.readText()
        // The shared apply layer must not depend on legacy parser / crypto.
        assertTrue(!content.contains("com.rescueauth.v2.legacy"))
    }

    // ------------------------------------------------------------------
    // Persistence (close/reopen)
    // ------------------------------------------------------------------

    @Test
    fun `apply then close reopen keeps imported state`() = runBlocking {
        context.deleteDatabase("merge-apply-reopen.db")
        // File-backed DB so data survives close/reopen.
        val fileDb = Room.databaseBuilder(context, RescueAuthDatabase::class.java, "merge-apply-reopen.db")
            .allowMainThreadQueries()
            .build()
        try {
            val fileSession = SecureSessionStateMachine().apply {
                beginAuthentication()
                onAuthenticationSuccess()
            }
            val r = VaultRepository(fileDb, fileSession)
            val snap = MergeTestData.fullSnapshot(
                MergeTestData.account("a1", "GitHub", "alice", totps = listOf(MergeTestData.totp("t1"))),
                developers = listOf(MergeTestData.apiCredential("api-1")),
            )
            r.applyMergePlan(payload(snap))
            fileDb.close()

            // Reopen the SAME database file: imported state must remain.
            val reopened = Room.databaseBuilder(context, RescueAuthDatabase::class.java, "merge-apply-reopen.db")
                .allowMainThreadQueries()
                .build()
            try {
                assertEquals(1, reopened.authAccountDao().count())
                assertEquals(1, reopened.totpCredentialDao().listAll().size)
                assertEquals(1, reopened.developerEntryDao().count())
                assertEquals("api-1", reopened.developerEntryDao().listAll().single().stableId)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase("merge-apply-reopen.db")
        }
    }
}
