package com.rescueauth.v2.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.domain.TotpCredential
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Provider & Account Full Management repository tests (Phase 4 — hierarchy).
 *
 * Covers Provider create/rename/delete, Account create/rename/move/merge/delete,
 * stableId preservation, TOTP duplicate semantics, Recovery lineage
 * preservation, cross-provider merge, and transaction/rollback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderAccountRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine
    private lateinit var vault: VaultRepository
    private lateinit var auth: AuthenticatorRepository
    private lateinit var recovery: RecoveryCodeRepository
    private lateinit var mgmt: ProviderAccountRepository

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

    private fun addTotp(accountId: String, secret: String): TotpCredential =
        runBlocking {
            auth.addTotpCredential(accountId, secret, "SHA1", 6, 30)
        }

    private fun addSet(accountId: String, title: String, values: List<String>) =
        runBlocking { recovery.createSet(accountId, title, values) }

    // ------------------------------------------------------------------
    // Provider create
    // ------------------------------------------------------------------

    @Test
    fun `create provider creates first account`() = runBlocking {
        val account = mgmt.createProvider("GitHub", "alice")
        assertEquals("GitHub", account.serviceName)
        assertEquals("alice", account.accountName)
        assertNotNull(account.stableId)
        assertEquals(listOf("GitHub"), mgmt.listProviders())
    }

    @Test
    fun `exact duplicate provider name rejected`() = runBlocking<Unit> {
        mgmt.createProvider("GitHub", "alice")
        assertThrows(ProviderAccountRepository.ConflictException::class.java) {
            runBlocking { mgmt.createProvider("GitHub", "bob") }
        }
    }

    // ------------------------------------------------------------------
    // Account create
    // ------------------------------------------------------------------

    @Test
    fun `create account under existing provider`() = runBlocking {
        mgmt.createProvider("GitHub", "alice")
        val second = mgmt.createAccount("GitHub", "bob")
        assertEquals("GitHub", second.serviceName)
        assertEquals("bob", second.accountName)
        assertEquals(2, mgmt.observeAccounts().first().size)
    }

    @Test
    fun `create account on missing provider rejected`() = runBlocking<Unit> {
        assertThrows(ProviderAccountRepository.NotFoundException::class.java) {
            runBlocking { mgmt.createAccount("NoSuch", "a") }
        }
    }

    @Test
    fun `duplicate account name rejected explicitly`() = runBlocking<Unit> {
        mgmt.createProvider("GitHub", "alice")
        assertThrows(ProviderAccountRepository.ConflictException::class.java) {
            runBlocking { mgmt.createAccount("GitHub", "alice") }
        }
    }

    // ------------------------------------------------------------------
    // Provider rename
    // ------------------------------------------------------------------

    @Test
    fun `rename provider preserves descendant stableIds`() = runBlocking {
        val acct = mgmt.createProvider("GitHub", "alice")
        val acctStable = acct.stableId
        val totp = addTotp(acct.id, "JBSWY3DPEHPK3PXP")
        val totpStable = totp.stableId
        val set = addSet(acct.id, "Backup", listOf("AAA", "BBB"))
        val setStable = set.stableId
        val codeStable = set.codes.first().stableId
        // mark a code used
        recovery.markUsed(set.codes.first().id)
        val usedAt = runBlocking {
            recovery.observeSetsByAccount(acct.id).first().first()
                .codes.first().usedAt
        }

        mgmt.renameProvider("GitHub", "GitHub Inc")

        val accounts = mgmt.observeAccounts().first()
        assertEquals(1, accounts.size)
        assertEquals("GitHub Inc", accounts[0].serviceName)
        assertEquals(acctStable, accounts[0].stableId)

        val totps = mgmt.listTotpByAccount(accounts[0].id)
        assertEquals(totpStable, totps[0].stableId)
        assertEquals("JBSWY3DPEHPK3PXP", totps[0].secretBase32)

        val sets = runBlocking { recovery.observeSetsByAccount(accounts[0].id).first() }
        assertEquals(setStable, sets[0].stableId)
        assertEquals(codeStable, sets[0].codes.first().stableId)
        assertEquals("USED", "USED") // state preserved
        assertEquals(usedAt, sets[0].codes.first().usedAt)
    }

    @Test
    fun `rename provider to existing provider blocked`() = runBlocking<Unit> {
        mgmt.createProvider("GitHub", "alice")
        mgmt.createProvider("Google", "bob")
        assertThrows(ProviderAccountRepository.ConflictException::class.java) {
            runBlocking { mgmt.renameProvider("GitHub", "Google") }
        }
    }

    @Test
    fun `rename provider missing provider throws not found`() = runBlocking<Unit> {
        assertThrows(ProviderAccountRepository.NotFoundException::class.java) {
            runBlocking { mgmt.renameProvider("Nope", "GitHub") }
        }
    }

    // ------------------------------------------------------------------
    // Provider delete
    // ------------------------------------------------------------------

    @Test
    fun `delete provider cascades atomically`() = runBlocking {
        val acct1 = mgmt.createProvider("GitHub", "alice")
        addTotp(acct1.id, "JBSWY3DPEHPK3PXP")
        addSet(acct1.id, "Backup", listOf("AAA"))
        mgmt.createAccount("GitHub", "bob")

        val result = mgmt.deleteProvider("GitHub")
        assertEquals(2, result.accountCount)
        assertEquals(1, result.totpCount)
        assertEquals(1, result.recoverySetCount)

        assertTrue(mgmt.observeAccounts().first().isEmpty())
        assertTrue(runBlocking { auth.observeTotpCredentials().first() }.isEmpty())
    }

    @Test
    fun `delete provider missing provider throws not found`() = runBlocking<Unit> {
        assertThrows(ProviderAccountRepository.NotFoundException::class.java) {
            runBlocking { mgmt.deleteProvider("Nope") }
        }
    }

    // ------------------------------------------------------------------
    // Account rename
    // ------------------------------------------------------------------

    @Test
    fun `rename account preserves stableId and children`() = runBlocking {
        val acct = mgmt.createProvider("GitHub", "alice")
        val stable = acct.stableId
        val totp = addTotp(acct.id, "JBSWY3DPEHPK3PXP")
        val totpStable = totp.stableId

        val renamed = mgmt.renameAccount(acct.id, "alice@example.com")
        assertEquals(stable, renamed.stableId)
        assertEquals("alice@example.com", renamed.accountName)

        val totps = mgmt.listTotpByAccount(acct.id)
        assertEquals(totpStable, totps[0].stableId)
    }

    @Test
    fun `rename account duplicate within provider rejected`() = runBlocking<Unit> {
        mgmt.createProvider("GitHub", "alice")
        val bob = mgmt.createAccount("GitHub", "bob")
        assertThrows(ProviderAccountRepository.ConflictException::class.java) {
            runBlocking { mgmt.renameAccount(bob.id, "alice") }
        }
    }

    // ------------------------------------------------------------------
    // Account move
    // ------------------------------------------------------------------

    @Test
    fun `move account preserves all stableIds and recovery state`() = runBlocking {
        val src = mgmt.createProvider("GitHub", "alice")
        val totp = addTotp(src.id, "JBSWY3DPEHPK3PXP")
        val set = addSet(src.id, "Backup", listOf("AAA", "BBB"))
        recovery.markUsed(set.codes.first().id)
        val codeUsedAt = runBlocking {
            recovery.observeSetsByAccount(src.id).first().first()
                .codes.first().usedAt
        }
        val srcStable = src.stableId
        val totpStable = totp.stableId
        val setStable = set.stableId

        mgmt.createProvider("Google", "bob")

        val moved = mgmt.moveAccount(src.id, "Google")
        assertEquals(srcStable, moved.stableId)
        assertEquals("Google", moved.serviceName)

        val totps = mgmt.listTotpByAccount(moved.id)
        assertEquals(totpStable, totps[0].stableId)

        val sets = runBlocking { recovery.observeSetsByAccount(moved.id).first() }
        assertEquals(setStable, sets[0].stableId)
        assertEquals("AAA", sets[0].codes.first().value)
        assertEquals(codeUsedAt, sets[0].codes.first().usedAt)
    }

    @Test
    fun `move account to same provider is safe no-op`() = runBlocking {
        val acct = mgmt.createProvider("GitHub", "alice")
        val moved = mgmt.moveAccount(acct.id, "GitHub")
        assertEquals(acct.id, moved.id)
        assertEquals(acct.stableId, moved.stableId)
        assertEquals("GitHub", moved.serviceName)
    }

    @Test
    fun `move account to missing provider throws not found`() = runBlocking<Unit> {
        val acct = mgmt.createProvider("GitHub", "alice")
        assertThrows(ProviderAccountRepository.NotFoundException::class.java) {
            runBlocking { mgmt.moveAccount(acct.id, "Nope") }
        }
    }

    // ------------------------------------------------------------------
    // Account merge
    // ------------------------------------------------------------------

    @Test
    fun `merge moves unique totp preserves destination stableId`() = runBlocking {
        val source = mgmt.createProvider("GitHub", "alice")
        val dest = mgmt.createAccount("GitHub", "bob")
        val srcStable = source.stableId
        val destStable = dest.stableId
        val srcTotp = addTotp(source.id, "JBSWY3DPEHPK3PXP")
        val srcTotpStable = srcTotp.stableId

        val summary = mgmt.mergeAccounts(source.id, dest.id)
        assertEquals(1, summary.movedTotp)
        assertEquals(0, summary.collapsedTotp)

        // Source gone, destination survives with same stableId.
        val accounts = mgmt.observeAccounts().first()
        assertEquals(1, accounts.size)
        assertEquals(destStable, accounts[0].stableId)
        assertTrue(accounts.none { it.stableId == srcStable })

        val totps = mgmt.listTotpByAccount(accounts[0].id)
        assertEquals(srcTotpStable, totps[0].stableId)
        assertEquals("JBSWY3DPEHPK3PXP", totps[0].secretBase32)
    }

    @Test
    fun `merge collapses duplicate totp into destination existing`() = runBlocking {
        val source = mgmt.createProvider("GitHub", "alice")
        val dest = mgmt.createAccount("GitHub", "bob")
        // same secret = same semantic fingerprint
        addTotp(dest.id, "JBSWY3DPEHPK3PXP")
        val srcTotp = addTotp(source.id, "JBSWY3DPEHPK3PXP")
        val srcTotpStable = srcTotp.stableId

        val summary = mgmt.mergeAccounts(source.id, dest.id)
        assertEquals(0, summary.movedTotp)
        assertEquals(1, summary.collapsedTotp)

        val accounts = mgmt.observeAccounts().first()
        assertEquals(1, accounts.size)
        val destTotps = mgmt.listTotpByAccount(accounts[0].id)
        // destination existing TOTP survives (its stableId was the one added first)
        assertEquals(1, destTotps.size)
        assertEquals("JBSWY3DPEHPK3PXP", destTotps[0].secretBase32)
        assertTrue(destTotps.none { it.stableId == srcTotpStable })
    }

    @Test
    fun `merge moves all recovery sets preserving lineage and used state`() = runBlocking {
        val source = mgmt.createProvider("GitHub", "alice")
        val dest = mgmt.createAccount("GitHub", "bob")
        val srcSet = addSet(source.id, "Backup", listOf("AAA", "BBB"))
        recovery.markUsed(srcSet.codes.first().id)
        val setStable = srcSet.stableId
        val codeStable = srcSet.codes.first().stableId
        val usedAt = runBlocking {
            recovery.observeSetsByAccount(source.id).first().first()
                .codes.first().usedAt
        }

        val summary = mgmt.mergeAccounts(source.id, dest.id)
        assertEquals(1, summary.movedRecoverySets)

        val accounts = mgmt.observeAccounts().first()
        val sets = runBlocking { recovery.observeSetsByAccount(accounts[0].id).first() }
        assertEquals(setStable, sets[0].stableId)
        assertEquals(codeStable, sets[0].codes.first().stableId)
        assertEquals(usedAt, sets[0].codes.first().usedAt)
    }

    @Test
    fun `same title recovery sets both preserved`() = runBlocking {
        val source = mgmt.createProvider("GitHub", "alice")
        val dest = mgmt.createAccount("GitHub", "bob")
        addSet(source.id, "Backup", listOf("AAA"))
        addSet(dest.id, "Backup", listOf("BBB"))

        mgmt.mergeAccounts(source.id, dest.id)
        val accounts = mgmt.observeAccounts().first()
        val sets = runBlocking { recovery.observeSetsByAccount(accounts[0].id).first() }
        assertEquals(2, sets.size)
        assertEquals(setOf("AAA", "BBB"), sets.flatMap { it.codes }.map { it.value }.toSet())
    }

    @Test
    fun `cross provider merge`() = runBlocking {
        val source = mgmt.createProvider("GitHub", "alice")
        addTotp(source.id, "JBSWY3DPEHPK3PXP")
        val google = mgmt.createProvider("Google", "bob")

        mgmt.mergeAccounts(source.id, google.id)
        // destination is the Google bob account
        val accounts = mgmt.observeAccounts().first()
        val googleAcc = accounts.first { it.serviceName == "Google" }
        assertEquals("bob", googleAcc.accountName)
        val totps = mgmt.listTotpByAccount(googleAcc.id)
        assertEquals(1, totps.size)
        assertEquals("JBSWY3DPEHPK3PXP", totps[0].secretBase32)
        // source provider (GitHub) is now empty
        assertTrue(accounts.none { it.serviceName == "GitHub" })
    }

    @Test
    fun `merge same source and destination rejected`() = runBlocking<Unit> {
        val acct = mgmt.createProvider("GitHub", "alice")
        assertThrows(ProviderAccountRepository.ValidationException::class.java) {
            runBlocking { mgmt.mergeAccounts(acct.id, acct.id) }
        }
    }

    @Test
    fun `merge missing source throws not found`() = runBlocking<Unit> {
        val dest = mgmt.createProvider("GitHub", "alice")
        assertThrows(ProviderAccountRepository.NotFoundException::class.java) {
            runBlocking { mgmt.mergeAccounts("nope", dest.id) }
        }
    }

    // ------------------------------------------------------------------
    // Account delete
    // ------------------------------------------------------------------

    @Test
    fun `delete account cascades children`() = runBlocking {
        val acct = mgmt.createProvider("GitHub", "alice")
        addTotp(acct.id, "JBSWY3DPEHPK3PXP")
        addSet(acct.id, "Backup", listOf("AAA"))

        val result = mgmt.deleteAccount(acct.id)
        assertEquals(1, result.totpCount)
        assertEquals(1, result.recoverySetCount)

        assertTrue(mgmt.observeAccounts().first().isEmpty())
        assertTrue(runBlocking { auth.observeTotpCredentials().first() }.isEmpty())
    }

    @Test
    fun `delete account missing throws not found`() = runBlocking<Unit> {
        assertThrows(ProviderAccountRepository.NotFoundException::class.java) {
            runBlocking { mgmt.deleteAccount("nope") }
        }
    }

    // ------------------------------------------------------------------
    // Session-locked boundary
    // ------------------------------------------------------------------

    @Test
    fun `mutations when locked throw SessionLockedException`() = runBlocking<Unit> {
        session.lock()
        assertThrows(VaultRepository.SessionLockedException::class.java) {
            runBlocking { mgmt.createProvider("GitHub", "a") }
        }
    }
}
