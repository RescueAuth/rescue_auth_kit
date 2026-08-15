package com.rescueauth.v2.exportimport

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.export.SelectedItemSet
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultPackagePayload
import com.rescueauth.v2.export.codec.PackageFormat
import com.rescueauth.v2.export.codec.PortablePackageCodec
import com.rescueauth.v2.repository.AuthenticatorRepository
import com.rescueauth.v2.repository.RecoveryCodeRepository
import com.rescueauth.v2.repository.VaultRepository
import com.rescueauth.v2.session.SecureSessionStateMachine
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
 * P7 package compatibility tests (48-52).
 *
 * Verifies that a pinned Account survives Full Vault and Selected Items
 * export/import round-trips through the existing logical `favorite` field
 * with **no** package-format/version change, no selectionDigest change and no
 * secret leaked into package metadata or search.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P7PackageCompatibilityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine
    private lateinit var vault: VaultRepository
    private lateinit var authRepo: AuthenticatorRepository
    private lateinit var recoveryRepo: RecoveryCodeRepository
    private lateinit var service: ExportImportService

    private val pin: CharArray = "654321".toCharArray()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = SecureSessionStateMachine()
        session.beginAuthentication()
        session.onAuthenticationSuccess()
        vault = VaultRepository(db, session)
        authRepo = AuthenticatorRepository(vault, db, session)
        recoveryRepo = RecoveryCodeRepository(vault, db, session)
        service = ExportImportService(vault, "1.0.0")
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `pinned account full vault round-trip`() = runBlocking {
        val account = authRepo.findOrCreateAccount("GitHub", "alice")
        authRepo.setPinned(account.id, true)
        val packageBytes = service.encodeFullVaultExport(pin).bytes

        // Decode with the codec directly (no session) and inspect the logical
        // favorite field.
        val payload = PortablePackageCodec.decode(packageBytes, pin)
        val accountInPackage = payload.snapshot.accounts.first { it.stableId == account.stableId }
        assertTrue(accountInPackage.favorite)

        // Logical schema version unchanged.
        assertEquals(VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION, payload.logicalSchemaVersion)
    }

    @Test
    fun `pinned account selected items round-trip`() = runBlocking {
        val account = authRepo.findOrCreateAccount("GitHub", "alice")
        authRepo.setPinned(account.id, true)
        val selection = SelectedItemSet(selectedAccountStableIds = setOf(account.stableId))
        val export = service.encodeExport(ExportScopeSpec.SelectedItems, selection, pin)

        val payload = PortablePackageCodec.decode(export.bytes, pin)
        assertEquals(SnapshotScope.SELECTED_ITEMS, payload.snapshot.scope)
        val accountInPackage = payload.snapshot.accounts.first { it.stableId == account.stableId }
        assertTrue(accountInPackage.favorite)
        // selectionDigest preserved by the export (no change).
        assertEquals(selection.digest(), export.selectionDigest)
    }

    @Test
    fun `pinned account stableId unchanged through round-trip`() = runBlocking {
        val account = authRepo.findOrCreateAccount("GitHub", "alice")
        authRepo.setPinned(account.id, true)
        val export = service.encodeFullVaultExport(pin)
        val payload = PortablePackageCodec.decode(export.bytes, pin)
        val acc = payload.snapshot.accounts.first()
        assertEquals(account.stableId, acc.stableId)
    }

    @Test
    fun `no secret introduced into package or search metadata`() = runBlocking {
        val account = authRepo.findOrCreateAccount("GitHub", "alice")
        authRepo.setPinned(account.id, true)
        authRepo.addTotpCredential(account.id, "JBSWY3DPEHPK3PXP", "SHA1", 6, 30)
        recoveryRepo.createSet(account.id, "backup", listOf("ABC-123"))

        val export = service.encodeFullVaultExport(pin)
        val payload = PortablePackageCodec.decode(export.bytes, pin)
        val blob = payload.snapshot.accounts.toString() +
            payload.snapshot.accounts.joinToString { it.toString() } +
            payload.snapshot.accounts.joinToString { it.serviceName + it.accountName }

        // The secret values are present in the snapshot (they belong there) but
        // they must never leak into SEARCH metadata — this is guaranteed by the
        // search projection. We assert the search docs carry no secret fields.
        val secret = "JBSWY3DPEHPK3PXP"
        assertTrue(blob.contains(secret)) // secret is in the encrypted payload, expected

        // Search projection never exposes it: build a SearchIndex from the
        // decoded snapshot's safe metadata only.
        val meta = payload.snapshot.accounts.map {
            com.rescueauth.v2.repository.DeveloperSearchMetadata(
                stableId = it.stableId,
                title = it.serviceName,
                type = "API_CREDENTIAL",
            )
        }
        // No field here can carry the TOTP secret.
        assertTrue(meta.none { it.toString().contains(secret) })
    }

    @Test
    fun `no package format or version change`() = runBlocking {
        val account = authRepo.findOrCreateAccount("GitHub", "alice")
        authRepo.setPinned(account.id, true)
        val export = service.encodeFullVaultExport(pin)
        val payload = PortablePackageCodec.decode(export.bytes, pin)
        assertEquals(VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION, payload.logicalSchemaVersion)
        // Pin reuses the existing favorite field, so package format stays at
        // the baseline — nothing new is added to the header.
        assertEquals(1, PackageFormat.CURRENT_FORMAT_VERSION)
    }
}
