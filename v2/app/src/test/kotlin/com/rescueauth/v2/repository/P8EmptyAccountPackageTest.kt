package com.rescueauth.v2.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.export.PackageSourceMetadata
import com.rescueauth.v2.export.SelectedItemSet
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultPackagePayload
import com.rescueauth.v2.export.VaultSnapshot
import com.rescueauth.v2.export.VaultSnapshotSelector
import com.rescueauth.v2.export.codec.PortablePackageCodec
import com.rescueauth.v2.session.SecureSessionStateMachine
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
 * P8 — Empty Account package regression (Issue #20 P8 §21–§23, §37, §66–§70).
 *
 * An explicitly-created Account with NO TOTP and NO Recovery Set is itself a
 * logical object. It must survive Full Vault export→import, Selected Items
 * export→import, and Authenticator-only round-trips, with its exact stableId,
 * pinned state and metadata preserved. This is a data-preservation guarantee,
 * NOT a schema/package/model redesign (tests 68–70).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P8EmptyAccountPackageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine
    private lateinit var vault: VaultRepository
    private lateinit var auth: AuthenticatorRepository
    private lateinit var recovery: RecoveryCodeRepository
    private lateinit var mgmt: ProviderAccountRepository

    private val pin = "p8-empty-account-pin"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = SecureSessionStateMachine()
        session.beginAuthentication()
        session.onAuthenticationSuccess()
        vault = VaultRepository(db, session)
        auth = AuthenticatorRepository(vault, db, session)
        recovery = RecoveryCodeRepository(vault, db, session)
        mgmt = ProviderAccountRepository(vault, db, session)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun encodeDecode(snapshot: VaultSnapshot): VaultSnapshot = runBlocking {
        val payload = VaultPackagePayload(
            logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
            packageId = "pkg-empty-account",
            createdAt = "2024-01-01T00:00:00Z",
            source = PackageSourceMetadata(client = "test", appVersion = "1.0.0"),
            snapshot = snapshot,
        )
        val bytes = PortablePackageCodec.encode(payload, pin)
        PortablePackageCodec.decode(bytes, pin).snapshot
    }

    private fun fullRoundTrip(): VaultSnapshot = runBlocking {
        encodeDecode(vault.buildConsistentExportSnapshot())
    }

    // 56 + 57: Full Vault snapshot contains the empty Account, and the package
    // encode/decode retains it (stableId exact, pinned + metadata preserved).
    @Test
    fun `full vault package retains empty account`() = runBlocking {
        val account = mgmt.createProvider("Google", "empty@example.com")
        auth.setPinned(account.id, true)
        mgmt.renameAccount(account.id, "empty@example.com")

        val snapshot = vault.buildConsistentExportSnapshot()
        assertEquals(1, snapshot.accounts.size)
        assertEquals("Google", snapshot.accounts[0].serviceName)
        assertTrue(snapshot.accounts[0].totpCredentials.isEmpty())
        assertTrue(snapshot.accounts[0].recoveryCodeSets.isEmpty())

        val decoded = fullRoundTrip()
        assertEquals(1, decoded.accounts.size)
        val a = decoded.accounts[0]
        assertEquals(account.stableId, a.stableId)
        assertEquals("Google", a.serviceName)
        assertEquals("empty@example.com", a.accountName)
        assertTrue(a.favorite)
        assertTrue(a.totpCredentials.isEmpty())
        assertTrue(a.recoveryCodeSets.isEmpty())
    }

    // 58 + 59: import the empty Account into an empty destination Vault creates
    // the Account, and the Provider grouping is visible via serviceName.
    @Test
    fun `import empty account into empty destination creates account`() = runBlocking {
        val source = VaultSnapshot(
            accounts = listOf(MergeTestData.account("acc-empty", "Google", "empty@example.com")),
            scope = SnapshotScope.FULL_VAULT,
        )
        val outcome = vault.applySnapshot(source, packageIdentity = "pkg-1")
        assertTrue(outcome is ImportOutcome.Applied)
        val applied = outcome as ImportOutcome.Applied
        assertEquals(1, applied.result.insertedAccounts)

        val rows = db.authAccountDao().listAll()
        assertEquals(1, rows.size)
        assertEquals("acc-empty", rows[0].stableId)
        assertEquals("Google", rows[0].serviceName)
        assertEquals("empty@example.com", rows[0].accountName)
    }

    // 63: repeated import of the same empty Account is idempotent.
    @Test
    fun `repeated empty account import is idempotent`() = runBlocking {
        val source = VaultSnapshot(
            accounts = listOf(MergeTestData.account("acc-empty", "Google", "empty@example.com")),
            scope = SnapshotScope.FULL_VAULT,
        )
        vault.applySnapshot(source, packageIdentity = "pkg-1")
        val second = vault.applySnapshot(source, packageIdentity = "pkg-1")
        assertTrue(second is ImportOutcome.Applied)
        assertEquals(0, (second as ImportOutcome.Applied).result.insertedAccounts)
        assertEquals(1, db.authAccountDao().count())
        assertEquals("acc-empty", db.authAccountDao().listAll()[0].stableId)
    }

    // 60 + 61 + 62: exact stableId, pinned state, and metadata preserved on
    // import of an empty Account.
    @Test
    fun `empty account pinned and metadata preserved on import`() = runBlocking {
        val source = VaultSnapshot(
            accounts = listOf(
                MergeTestData.account("acc-empty", "Google", "empty@example.com").copy(
                    favorite = true,
                    notes = "my empty account",
                ),
            ),
            scope = SnapshotScope.FULL_VAULT,
        )
        vault.applySnapshot(source, packageIdentity = "pkg-1")
        val row = db.authAccountDao().listAll().single()
        assertEquals("acc-empty", row.stableId)
        assertTrue(row.favorite)
        assertEquals("my empty account", row.notes)
        assertEquals("Google", row.serviceName)
    }

    // 64 + 65: Selected Items export of the empty Account is non-empty / valid,
    // and import into an empty destination restores the empty Account.
    @Test
    fun `selected items export and import preserves empty account`() = runBlocking {
        val account = mgmt.createProvider("Google", "empty@example.com")
        val full = vault.buildConsistentExportSnapshot()

        // Select ONLY the empty account (no children selected).
        val selection = SelectedItemSet(selectedAccountStableIds = setOf(account.stableId))
        val selected = VaultSnapshotSelector.selected(full, selection)
        assertEquals(SnapshotScope.SELECTED_ITEMS, selected.scope)
        assertEquals(1, selected.accounts.size)
        assertEquals(account.stableId, selected.accounts[0].stableId)
        assertTrue(selected.accounts[0].totpCredentials.isEmpty())
        assertTrue(selected.accounts[0].recoveryCodeSets.isEmpty())

        // Round-trip through the codec (non-empty/valid package).
        val decoded = encodeDecode(selected)
        assertEquals(1, decoded.accounts.size)

        // Import into an EMPTY destination Vault (this test's fresh DB has the
        // empty account, so use a separate fresh DB to prove empty-destination
        // creation).
        val fresh = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val freshSession = SecureSessionStateMachine().apply {
                beginAuthentication(); onAuthenticationSuccess()
            }
            val freshVault = VaultRepository(fresh, freshSession)
            val out = freshVault.applySnapshot(decoded, packageIdentity = "pkg-selected")
            assertTrue(out is ImportOutcome.Applied)
            val applied = out as ImportOutcome.Applied
            assertEquals(1, applied.result.insertedAccounts)
            assertEquals(1, fresh.authAccountDao().count())
            assertEquals(account.stableId, fresh.authAccountDao().listAll()[0].stableId)
        } finally {
            fresh.close()
        }
    }

    // 66: Authenticator-only round-trip retains the empty Account (the current
    // AUTHENTICATOR_ONLY scope keeps all account containers, including empty).
    @Test
    fun `authenticator only round trip retains empty account`() = runBlocking {
        val account = mgmt.createProvider("Google", "empty@example.com")
        val full = vault.buildConsistentExportSnapshot()
        val authOnly = VaultSnapshotSelector.authenticatorOnly(full)
        assertEquals(SnapshotScope.AUTHENTICATOR_ONLY, authOnly.scope)
        assertEquals(1, authOnly.accounts.size)
        assertEquals(account.stableId, authOnly.accounts[0].stableId)

        val decoded = encodeDecode(authOnly)
        assertEquals(1, decoded.accounts.size)

        val out = vault.applySnapshot(decoded, packageIdentity = "pkg-auth")
        assertTrue(out is ImportOutcome.Applied)
        assertEquals(1, db.authAccountDao().count())
        assertEquals(account.stableId, db.authAccountDao().listAll()[0].stableId)
    }

    // 67: deleting an empty account then re-planning from the original package
    // still recreates the empty account correctly (the planner treats the
    // account container as a first-class object).
    @Test
    fun `replan after empty account delete recreates it`() = runBlocking {
        val account = mgmt.createProvider("Google", "empty@example.com")
        val stableId = account.stableId
        mgmt.deleteAccount(account.id)
        assertEquals(0, db.authAccountDao().count())

        val source = VaultSnapshot(
            accounts = listOf(MergeTestData.account(stableId, "Google", "empty@example.com")),
            scope = SnapshotScope.FULL_VAULT,
        )
        val out = vault.applySnapshot(source, packageIdentity = "pkg-recreate")
        assertTrue(out is ImportOutcome.Applied)
        assertEquals(1, db.authAccountDao().count())
        assertEquals(stableId, db.authAccountDao().listAll()[0].stableId)
        assertEquals("Google", db.authAccountDao().listAll()[0].serviceName)
    }
}
