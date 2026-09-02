package com.rescueauth.v2.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.PROVIDER_ICON_LETTER
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.export.PackageSourceMetadata
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultAccount
import com.rescueauth.v2.export.VaultPackagePayload
import com.rescueauth.v2.export.VaultProviderIcon
import com.rescueauth.v2.export.VaultSnapshot
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Provider icon metadata tests (schema v4 `provider_meta`).
 *
 * Covers: set/get round trip, AUTO (null) reset, the "letter" sentinel,
 * rename/delete cascade with the virtual provider, snapshot carry-out
 * (export includes non-blank overrides only), and the import fill-missing
 * policy (a package value never overwrites a local override and only fills
 * providers that exist locally).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderIconMetaTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine
    private lateinit var vault: VaultRepository
    private lateinit var auth: AuthenticatorRepository
    private lateinit var mgmt: ProviderAccountRepository

    private val pin = "icon-meta-pin-1234"

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
        mgmt = ProviderAccountRepository(vault, db, session)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- set / get / reset ----

    @Test
    fun `set and get provider icon round trips`() = runBlocking {
        mgmt.createProvider("GitHub", "alice")
        mgmt.setProviderIcon("GitHub", "github")
        assertEquals("github", mgmt.getProviderIcon("GitHub"))
    }

    @Test
    fun `null key resets to AUTO by deleting the row`() = runBlocking {
        mgmt.createProvider("GitHub", "alice")
        mgmt.setProviderIcon("GitHub", "github")
        mgmt.setProviderIcon("GitHub", null)
        assertNull(mgmt.getProviderIcon("GitHub"))
    }

    @Test
    fun `letter sentinel persists verbatim`() = runBlocking {
        mgmt.createProvider("Google", "bob")
        mgmt.setProviderIcon("Google", PROVIDER_ICON_LETTER)
        assertEquals(PROVIDER_ICON_LETTER, mgmt.getProviderIcon("Google"))
    }

    @Test
    fun `setting an icon for an unknown provider throws`() {
        assertThrows(Exception::class.java) {
            runBlocking { mgmt.setProviderIcon("Ghost", "github") }
        }
    }

    @Test
    fun `icon overrides are observable`() = runBlocking {
        mgmt.createProvider("GitHub", "alice")
        mgmt.createProvider("Google", "bob")
        mgmt.setProviderIcon("GitHub", "github")
        val icons = mgmt.observeProviderIcons().first()
        assertEquals("github", icons["GitHub"])
        assertNull(icons["Google"])
    }

    // ---- cascade with the virtual provider identity ----

    @Test
    fun `rename provider cascades the meta row`() = runBlocking {
        mgmt.createProvider("GitHub", "alice")
        mgmt.setProviderIcon("GitHub", "github")
        mgmt.renameProvider("GitHub", "GitHub Inc")
        assertNull(mgmt.getProviderIcon("GitHub"))
        assertEquals("github", mgmt.getProviderIcon("GitHub Inc"))
    }

    @Test
    fun `delete provider cascades the meta row`() = runBlocking {
        mgmt.createProvider("GitHub", "alice")
        mgmt.setProviderIcon("GitHub", "github")
        mgmt.deleteProvider("GitHub")
        assertNull(mgmt.getProviderIcon("GitHub"))
    }

    // ---- export: snapshot carries the overrides ----

    @Test
    fun `export snapshot carries non-blank icon overrides`() = runBlocking {
        mgmt.createProvider("GitHub", "alice")
        mgmt.createProvider("Google", "bob")
        mgmt.setProviderIcon("GitHub", "github")
        mgmt.setProviderIcon("Google", PROVIDER_ICON_LETTER)

        val snapshot = vault.buildConsistentExportSnapshot()
        assertEquals(
            setOf(
                VaultProviderIcon("GitHub", "github"),
                VaultProviderIcon("Google", PROVIDER_ICON_LETTER),
            ),
            snapshot.providerIcons.toSet(),
        )
    }

    @Test
    fun `providers without overrides export an empty icon list`() = runBlocking {
        mgmt.createProvider("GitHub", "alice")
        val snapshot = vault.buildConsistentExportSnapshot()
        assertEquals(0, snapshot.providerIcons.size)
    }

    // ---- import: fill-missing policy ----

    private fun payloadOf(
        packageId: String,
        accounts: List<VaultAccount>,
        icons: List<VaultProviderIcon>,
    ) = VaultPackagePayload(
        logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
        packageId = packageId,
        createdAt = "2024-01-01T00:00:00Z",
        source = PackageSourceMetadata(client = "test", appVersion = "1.0.0"),
        snapshot = VaultSnapshot(
            scope = SnapshotScope.FULL_VAULT,
            accounts = accounts,
            providerIcons = icons,
        ),
    )

    private fun account(serviceName: String, accountName: String) = VaultAccount(
        stableId = "acc-$serviceName-$accountName",
        serviceName = serviceName,
        accountName = accountName,
        favorite = false,
        notes = null,
        sortOrder = 0,
        createdAt = "2024-01-01T00:00:00Z",
        updatedAt = "2024-01-01T00:00:00Z",
        totpCredentials = emptyList(),
        recoveryCodeSets = emptyList(),
    )

    @Test
    fun `import fills icon for a provider with no local override`() = runBlocking {
        mgmt.createProvider("GitLab", "carol") // exists locally, no override
        val payload = payloadOf(
            packageId = "pkg-icons-1",
            accounts = listOf(account("GitHub", "alice")),
            icons = listOf(
                VaultProviderIcon("GitHub", "github"),
                VaultProviderIcon("GitLab", "gitlab"),
            ),
        )
        val outcome = vault.applyMergePlan(payload)
        assertEquals(true, outcome is ImportOutcome.Applied)

        assertEquals("github", mgmt.getProviderIcon("GitHub")) // imported with its account
        assertEquals("gitlab", mgmt.getProviderIcon("GitLab")) // fill-missing
    }

    @Test
    fun `import never overwrites a local override`() = runBlocking {
        mgmt.createProvider("GitHub", "alice")
        mgmt.setProviderIcon("GitHub", PROVIDER_ICON_LETTER) // user's own choice
        val payload = payloadOf(
            packageId = "pkg-icons-2",
            accounts = listOf(account("GitHub", "alice")),
            icons = listOf(VaultProviderIcon("GitHub", "github")),
        )
        vault.applyMergePlan(payload)
        assertEquals(PROVIDER_ICON_LETTER, mgmt.getProviderIcon("GitHub"))
    }

    @Test
    fun `import skips icons for providers that do not exist locally`() = runBlocking {
        val payload = payloadOf(
            packageId = "pkg-icons-3",
            accounts = emptyList(),
            icons = listOf(VaultProviderIcon("Dropbox", "dropbox")),
        )
        vault.applyMergePlan(payload)
        assertNull(mgmt.getProviderIcon("Dropbox"))
    }

    // ---- package round trip retains the section ----

    @Test
    fun `codec round trip retains provider icons`() = runBlocking {
        mgmt.createProvider("GitHub", "alice")
        mgmt.setProviderIcon("GitHub", "github")
        val snapshot = vault.buildConsistentExportSnapshot()
        val payload = payloadOf("pkg-icons-4", snapshot.accounts, snapshot.providerIcons)
        val bytes = com.rescueauth.v2.export.codec.PortablePackageCodec.encode(payload, pin)
        val decoded = com.rescueauth.v2.export.codec.PortablePackageCodec.decode(bytes, pin).snapshot
        assertEquals(listOf(VaultProviderIcon("GitHub", "github")), decoded.providerIcons)
    }
}
