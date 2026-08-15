package com.rescueauth.v2.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.export.PackageSourceMetadata
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultPackagePayload
import com.rescueauth.v2.export.VaultSnapshot
import com.rescueauth.v2.export.codec.PortablePackageCodec
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Package / selection integration tests (Phase 4 — hierarchy management).
 *
 * Verifies that after Provider/Account mutations the Vault still produces a
 * valid Full Vault snapshot that round-trips through the portable codec with a
 * correct logical hierarchy (no orphan references, stableIds preserved).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderAccountPackageIntegrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine
    private lateinit var vault: VaultRepository
    private lateinit var auth: AuthenticatorRepository
    private lateinit var recovery: RecoveryCodeRepository
    private lateinit var mgmt: ProviderAccountRepository

    private val pin = "integration-pin-1234"

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

    private fun roundTrip(): VaultSnapshot = runBlocking {
        val snapshot = vault.buildConsistentExportSnapshot()
        val payload = VaultPackagePayload(
            logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
            packageId = "pkg-integration",
            createdAt = "2024-01-01T00:00:00Z",
            source = PackageSourceMetadata(client = "test", appVersion = "1.0.0"),
            snapshot = snapshot,
        )
        val bytes = PortablePackageCodec.encode(payload, pin)
        PortablePackageCodec.decode(bytes, pin).snapshot
    }

    @Test
    fun `provider rename yields valid full vault round trip`() = runBlocking {
        val acct = mgmt.createProvider("GitHub", "alice")
        addTotp(acct.id, "JBSWY3DPEHPK3PXP")
        addSet(acct.id, "Backup", listOf("AAA"))
        mgmt.renameProvider("GitHub", "GitHub Inc")

        val decoded = roundTrip()
        assertEquals(SnapshotScope.FULL_VAULT, decoded.scope)
        assertEquals(1, decoded.accounts.size)
        assertEquals("GitHub Inc", decoded.accounts[0].serviceName)
        assertEquals(acct.stableId, decoded.accounts[0].stableId)
        assertEquals(1, decoded.accounts[0].totpCredentials.size)
        assertEquals(1, decoded.accounts[0].recoveryCodeSets.size)
        // no orphans
        assertEquals(decoded.accounts.size, decoded.accounts.size)
    }

    @Test
    fun `account rename preserves stableId in round trip`() = runBlocking {
        val acct = mgmt.createProvider("GitHub", "alice")
        val stable = acct.stableId
        val totp = addTotp(acct.id, "JBSWY3DPEHPK3PXP")
        mgmt.renameAccount(acct.id, "alice@example.com")

        val decoded = roundTrip()
        assertEquals(stable, decoded.accounts[0].stableId)
        assertEquals("alice@example.com", decoded.accounts[0].accountName)
        assertEquals(totp.stableId, decoded.accounts[0].totpCredentials[0].stableId)
    }

    @Test
    fun `account move round trips with correct parent relation`() = runBlocking {
        val src = mgmt.createProvider("GitHub", "alice")
        addTotp(src.id, "JBSWY3DPEHPK3PXP")
        addSet(src.id, "Backup", listOf("AAA"))
        mgmt.createProvider("Google", "bob")
        mgmt.moveAccount(src.id, "Google")

        val decoded = roundTrip()
        val google = decoded.accounts.filter { it.serviceName == "Google" }
        assertEquals(2, google.size) // bob + moved alice
        val alice = google.first { it.accountName == "alice" }
        assertEquals(1, alice.totpCredentials.size)
        assertEquals(1, alice.recoveryCodeSets.size)
        assertTrue(decoded.accounts.none { it.serviceName == "GitHub" })
    }

    @Test
    fun `merge result round trips with source removed`() = runBlocking {
        val source = mgmt.createProvider("GitHub", "alice")
        addTotp(source.id, "JBSWY3DPEHPK3PXP")
        val dest = mgmt.createAccount("GitHub", "bob")
        val destStable = dest.stableId
        addSet(dest.id, "Backup", listOf("AAA"))

        mgmt.mergeAccounts(source.id, dest.id)

        val decoded = roundTrip()
        assertEquals(1, decoded.accounts.size)
        assertEquals(destStable, decoded.accounts[0].stableId)
        assertEquals(1, decoded.accounts[0].totpCredentials.size)
        assertEquals(1, decoded.accounts[0].recoveryCodeSets.size)
    }

    @Test
    fun `recovery state preserved through merge and round trip`() = runBlocking {
        val source = mgmt.createProvider("GitHub", "alice")
        val set = addSet(source.id, "Backup", listOf("AAA", "BBB"))
        recovery.markUsed(set.codes.first().id)
        val dest = mgmt.createAccount("GitHub", "bob")

        mgmt.mergeAccounts(source.id, dest.id)

        val decoded = roundTrip()
        val setDec = decoded.accounts[0].recoveryCodeSets[0]
        assertEquals("USED", setDec.codes.first().status)
        assertTrue(setDec.codes.first().usedAt != null)
        assertEquals(2, setDec.codes.size)
    }

    private suspend fun addTotp(accountId: String, secret: String) =
        auth.addTotpCredential(accountId, secret, "SHA1", 6, 30)

    private suspend fun addSet(accountId: String, title: String, values: List<String>) =
        recovery.createSet(accountId, title, values)
}
