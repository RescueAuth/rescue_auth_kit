package com.rescueauth.v2.ui.navigation

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.search.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SearchDestinationsTest {
    @Test fun recoveryResultOpensTheOwningAccountsRecoveryPage() {
        val result = SearchResult.RecoverySet("Backup codes", null, "recovery-set-1", false, accountId = "account-1")
        assertEquals("authenticator/account/account-1/recovery", result.vaultDestination())
    }

    @Test fun totpResultOpensTheOwningAccountRatherThanTheCredentialId() {
        val result = SearchResult.Totp("Workspace", null, "credential-1", false, accountId = "account-1")
        assertEquals("authenticator/account-detail/account-1", result.vaultDestination())
    }

    @Test fun providerNamesRemainOneEncodedNavigationArgument() {
        val name = "Work / personal?#"
        val result = SearchResult.Provider(name, null, name, accountCount = 1)
        val argument = result.vaultDestination().removePrefix("authenticator/provider/")
        assertTrue(argument.contains("%2F"))
        assertTrue(argument.contains("%3F"))
        assertEquals(name, Uri.decode(argument))
    }

    @Test fun developerStableIdentityRoundTripsThroughNavigation() {
        val id = "legacy:entry/one?revision#two"
        val result = SearchResult.Developer("Deployment", null, id, developerType = "SSH_KEY")
        val argument = result.vaultDestination().removePrefix("developer/entry/")
        assertTrue(argument.contains("%2F"))
        assertEquals(id, Uri.decode(argument))
    }
}
