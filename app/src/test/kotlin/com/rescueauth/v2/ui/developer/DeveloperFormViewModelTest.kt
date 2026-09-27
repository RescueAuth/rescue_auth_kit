package com.rescueauth.v2.ui.developer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.export.VaultKeyValue
import com.rescueauth.v2.repository.DeveloperRepository
import com.rescueauth.v2.repository.VaultRepository
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.security.FakeSensitiveActionPrompt
import com.rescueauth.v2.security.SensitiveActionGate
import com.rescueauth.v2.security.SensitiveActionRequest
import com.rescueauth.v2.security.SensitiveActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 4 P4 Developer form ViewModel tests (Issue #20 §18/§20/§26–§28):
 *
 * - create API/SSH/Generic via the form;
 * - edit preserves the same stableId;
 * - generic add/remove field rows;
 * - validation errors never echo secrets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeveloperFormViewModelTest {

    @Test fun `edit cannot load a protected entry without an authentication gate`() = runBlocking {
        val created = repo.createGenericSecret("Demo", null, listOf(VaultKeyValue("field", "fixture-value")))
        var reads = 0
        val model = DeveloperFormViewModel(
            developerRepositoryProvider = { reads++; repo }, sessionState = session.state,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        model.beginEdit(created.stableId)
        assertEquals(0, reads)
        assertEquals(null, model.formState.value.editingStableId)
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine
    private lateinit var vault: VaultRepository
    private lateinit var repo: DeveloperRepository

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

    private fun vm(scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)) =
        DeveloperFormViewModel(
            developerRepositoryProvider = { repo },
            sessionState = session.state,
            scope = scope,
            sensitiveActionGate = SensitiveActionGate(
                prompt = object : FakeSensitiveActionPrompt() {
                    override fun tryStart(request: SensitiveActionRequest, title: CharSequence, subtitle: CharSequence?,
                        onResult: (SensitiveActionResult) -> Unit): Boolean {
                        onResult(SensitiveActionResult.Success(request))
                        return true
                    }
                },
                session = session, titleProvider = { "Verify" }, subtitleProvider = { null },
            ),
        )

    @Test
    fun `create api credential via form`() = runBlocking {
        val vm = vm()
        vm.beginCreate(DeveloperFormType.API_CREDENTIAL)
        vm.onTitleChange("Stripe")
        vm.onServiceNameChange("stripe")
        vm.onAccountNameChange("alice")
        vm.onApiKeyChange("sk_live_123")
        vm.onApiSecretChange("secret-abc")

        assertTrue(vm.submit())
        val entries = repo.observeAll().first()
        assertEquals(1, entries.size)
        assertEquals("Stripe", entries[0].title)
    }

    @Test
    fun `edit api credential preserves stableId`() = runBlocking {
        val created = repo.createApiCredential(
            title = "Old", notes = null,
            serviceName = "svc", accountName = "acc",
            apiKey = "k1", apiSecret = "s1",
        )
        val vm = vm()
        vm.beginEdit(created.stableId)
        assertEquals(DeveloperFormType.API_CREDENTIAL, vm.formState.value.type)
        vm.onTitleChange("New")
        vm.onApiKeyChange("k2")
        vm.onApiSecretChange("s2")
        assertTrue(vm.submit())

        val read = repo.getByStableId(created.stableId)!!
        assertEquals("New", read.title)
        assertEquals(created.stableId, read.stableId)
        assertEquals(1, repo.observeAll().first().size)
    }

    @Test
    fun `create ssh key via form`() = runBlocking {
        val vm = vm()
        vm.beginCreate(DeveloperFormType.SSH_KEY)
        vm.onTitleChange("deploy")
        vm.onKeyNameChange("deploy-2026")
        vm.onPrivateKeyChange("-----BEGIN OPENSSH PRIVATE KEY-----\nXXX\n-----END-----")
        vm.onPassphraseChange("phrase")
        assertTrue(vm.submit())
        assertEquals(1, repo.observeAll().first().size)
        val read = repo.getByStableId(repo.observeAll().first()[0].stableId)!!
        assertEquals("deploy-2026", (read as com.rescueauth.v2.export.VaultSshKey).keyName)
        assertEquals("phrase", read.passphrase)
    }

    @Test
    fun `create generic secret with multiple fields via form`() = runBlocking {
        val vm = vm()
        vm.beginCreate(DeveloperFormType.GENERIC_SECRET)
        vm.onTitleChange("Wi-Fi")
        vm.onFieldLabelChange(0, "network")
        vm.onFieldValueChange(0, "guest")
        vm.addField()
        vm.onFieldLabelChange(1, "password")
        vm.onFieldValueChange(1, "wifi-pass")
        assertTrue(vm.submit())

        val read = repo.getByStableId(repo.observeAll().first()[0].stableId) as com.rescueauth.v2.export.VaultGenericSecret
        assertEquals(2, read.fields.size)
        assertEquals("wifi-pass", read.fields.first { it.key == "password" }.value)
    }

    @Test
    fun `generic edit preserves stableId and field labels`() = runBlocking {
        val created = repo.createGenericSecret(
            title = "G", notes = null,
            fields = listOf(VaultKeyValue("k1", "v1"), VaultKeyValue("k2", "v2")),
        )
        val vm = vm()
        vm.beginEdit(created.stableId)
        assertEquals(2, vm.formState.value.fields.size)
        vm.onTitleChange("G2")
        vm.onFieldValueChange(0, "v1-edit")
        vm.removeField(1)
        assertEquals(1, vm.formState.value.fields.size)
        assertTrue(vm.submit())

        val read = repo.getByStableId(created.stableId) as com.rescueauth.v2.export.VaultGenericSecret
        assertEquals(created.stableId, read.stableId)
        assertEquals("G2", read.title)
        assertEquals(1, read.fields.size)
        assertEquals("v1-edit", read.fields[0].value)
    }

    @Test
    fun `validation error does not echo the secret`() = runBlocking {
        val vm = vm()
        vm.beginCreate(DeveloperFormType.API_CREDENTIAL)
        vm.onTitleChange("T")
        vm.onServiceNameChange("s")
        vm.onAccountNameChange("a")
        vm.onApiKeyChange("SECRET-KEY-VALUE")
        vm.onApiSecretChange("")
        assertFalse(vm.submit())
        // The error message must not echo the secret (Issue #20 §20). The form
        // itself holds the transient apiKey by design; the ERROR must not.
        assertTrue(!(vm.formState.value.error ?: "").contains("SECRET-KEY-VALUE"))
        assertTrue(!(vm.formState.value.error ?: "").contains("s3cret"))
        assertEquals(0, repo.observeAll().first().size)
    }

    @Test
    fun `form fields are transient - reset on dismiss`() = runBlocking {
        val vm = vm()
        vm.beginCreate(DeveloperFormType.API_CREDENTIAL)
        vm.onTitleChange("T")
        vm.onApiKeyChange("k")
        vm.dismiss()
        assertEquals("", vm.formState.value.title)
        assertEquals("", vm.formState.value.apiKey)
    }
}
