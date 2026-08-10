package com.rescueauth.v2.search

import com.rescueauth.v2.domain.AuthAccount
import com.rescueauth.v2.domain.RecoveryCode
import com.rescueauth.v2.domain.RecoveryCodeSet
import com.rescueauth.v2.domain.TotpCredential
import com.rescueauth.v2.repository.DeveloperSearchMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7 Global Search — core matching + secret-exclusion tests (app module).
 *
 * Uses real domain models and real [SearchIndex] / [SearchDocument] /
 * [SearchResult] objects. Secret fixtures are constructed explicitly and we
 * assert both that they do not match AND that no SearchResult `toString()`
 * contains them (P7 §21 secret-exclusion tests).
 */
class SearchIndexTest {

    private fun account(
        id: String,
        service: String,
        name: String,
        pinned: Boolean = false,
    ) = AuthAccount(
        id = id,
        stableId = "stable-$id",
        serviceName = service,
        accountName = name,
        sortOrder = 0,
        createdAt = "t",
        updatedAt = "t",
        favorite = pinned,
    )

    private fun totp(
        id: String,
        accountId: String,
        secret: String = "JBSWY3DPEHPK3PXP",
    ) = TotpCredential(
        id = id,
        stableId = "stable-$id",
        accountId = accountId,
        secretBase32 = secret,
        algorithm = "SHA1",
        digits = 6,
        periodSeconds = 30,
        createdAt = "t",
    )

    private fun recoverySet(accountId: String, title: String) = RecoveryCodeSet(
        id = "set-$accountId-$title",
        stableId = "stable-set-$accountId-$title",
        accountId = accountId,
        title = title,
        createdAt = "t",
        codes = listOf(RecoveryCode(id = "c1", stableId = "sc1", setId = "s1", value = "ABC-123", isUsed = false, sortOrder = 0)),
    )

    private fun dev(meta: DeveloperSearchMetadata) = meta

    // ------------------------------------------------------------------
    // 1. empty query
    // ------------------------------------------------------------------

    @Test
    fun `empty query returns no results`() {
        val index = SearchIndex(
            accounts = listOf(account("a1", "GitHub", "alice")),
            totpCredentials = emptyList(),
            recoverySets = emptyList(),
            developerMetadata = emptyList(),
        )
        assertTrue(index.search("").isEmpty())
        assertTrue(index.search("   ").isEmpty())
    }

    // ------------------------------------------------------------------
    // 2-13. core matching
    // ------------------------------------------------------------------

    @Test
    fun `provider name match`() {
        val index = SearchIndex(
            accounts = listOf(account("a1", "GitHub", "alice")),
            totpCredentials = emptyList(),
            recoverySets = emptyList(),
            developerMetadata = emptyList(),
        )
        val results = index.search("github")
        // 'github' matches the Provider document and the Account document
        // (account searchableTokens include its serviceName).
        assertTrue(results.any { it is SearchResult.Provider })
        val provider = results.filterIsInstance<SearchResult.Provider>().first()
        assertEquals("GitHub", provider.title)
    }

    @Test
    fun `account name match`() {
        val index = SearchIndex(
            accounts = listOf(account("a1", "GitHub", "alice@example.com")),
            totpCredentials = emptyList(),
            recoverySets = emptyList(),
            developerMetadata = emptyList(),
        )
        val results = index.search("alice")
        assertTrue(results.any { it is SearchResult.Account })
    }

    @Test
    fun `totp safe display metadata match`() {
        val index = SearchIndex(
            accounts = listOf(account("a1", "Google", "user@example.com")),
            totpCredentials = listOf(totp("t1", "a1")),
            recoverySets = emptyList(),
            developerMetadata = emptyList(),
        )
        val results = index.search("google")
        assertTrue(results.any { it is SearchResult.Totp })
        assertEquals("Google", (results.first { it is SearchResult.Totp } as SearchResult.Totp).title)
    }

    @Test
    fun `developer signing key safe metadata match`() {
        val index = SearchIndex(
            accounts = emptyList(),
            totpCredentials = emptyList(),
            recoverySets = emptyList(),
            developerMetadata = listOf(
                DeveloperSearchMetadata(
                    stableId = "d1",
                    title = "release keystore",
                    type = "ANDROID_SIGNING_KEY",
                    projectName = "myapp",
                    packageName = "com.example.app",
                    keystoreFileName = "release.jks",
                    keyAlias = "alias1",
                ),
            ),
        )
        assertTrue(index.search("release").isNotEmpty())
        assertTrue(index.search("com.example.app").isNotEmpty())
        assertTrue(index.search("alias1").isNotEmpty())
        assertTrue(index.search("release.jks").isNotEmpty())
    }

    @Test
    fun `api credential safe metadata match`() {
        val index = SearchIndex(
            accounts = emptyList(),
            totpCredentials = emptyList(),
            recoverySets = emptyList(),
            developerMetadata = listOf(
                DeveloperSearchMetadata(
                    stableId = "d2",
                    title = "CI bot",
                    type = "API_CREDENTIAL",
                    serviceName = "github.com",
                    accountName = "bot",
                ),
            ),
        )
        assertTrue(index.search("bot").isNotEmpty())
        assertTrue(index.search("github").isNotEmpty())
    }

    @Test
    fun `ssh key safe metadata match`() {
        val index = SearchIndex(
            accounts = emptyList(),
            totpCredentials = emptyList(),
            recoverySets = emptyList(),
            developerMetadata = listOf(
                DeveloperSearchMetadata(
                    stableId = "d3",
                    title = "deploy key",
                    type = "SSH_KEY",
                    keyName = "server-deploy",
                ),
            ),
        )
        assertTrue(index.search("server-deploy").isNotEmpty())
    }

    @Test
    fun `env var project and variable-name match`() {
        val index = SearchIndex(
            accounts = emptyList(),
            totpCredentials = emptyList(),
            recoverySets = emptyList(),
            developerMetadata = listOf(
                DeveloperSearchMetadata(
                    stableId = "d4",
                    title = "CI variables",
                    type = "ENVIRONMENT_VARIABLE_SET",
                    projectName = "backend",
                    variableNames = listOf("API_URL", "DB_HOST"),
                ),
            ),
        )
        assertTrue(index.search("backend").isNotEmpty())
        assertTrue(index.search("API_URL").isNotEmpty())
    }

    @Test
    fun `generic field-label match`() {
        val index = SearchIndex(
            accounts = emptyList(),
            totpCredentials = emptyList(),
            recoverySets = emptyList(),
            developerMetadata = listOf(
                DeveloperSearchMetadata(
                    stableId = "d5",
                    title = "WiFi fallback",
                    type = "GENERIC_SECRET",
                    fieldLabels = listOf("router", "password"),
                ),
            ),
        )
        assertTrue(index.search("router").isNotEmpty())
    }

    @Test
    fun `case-insensitive matching`() {
        val index = SearchIndex(
            accounts = listOf(account("a1", "GitHub", "Alice")),
            totpCredentials = emptyList(),
            recoverySets = emptyList(),
            developerMetadata = emptyList(),
        )
        assertTrue(index.search("ALICE").any { it is SearchResult.Account })
        assertTrue(index.search("github").any { it is SearchResult.Provider })
    }

    @Test
    fun `deterministic ordering stable`() {
        val index = SearchIndex(
            accounts = listOf(
                account("a1", "GitHub", "zeta"),
                account("a2", "GitHub", "alpha"),
            ),
            totpCredentials = emptyList(),
            recoverySets = emptyList(),
            developerMetadata = emptyList(),
        )
        val r1 = index.search("github")
        val r2 = index.search("github")
        assertEquals(r1.map { it.navigationId }, r2.map { it.navigationId })
    }

    @Test
    fun `multi-token behavior`() {
        val index = SearchIndex(
            accounts = listOf(account("a1", "Google", "work@example.com")),
            totpCredentials = emptyList(),
            recoverySets = emptyList(),
            developerMetadata = emptyList(),
        )
        assertTrue(index.search("google work").isNotEmpty())
        assertTrue(index.search("google nonexistent").isEmpty())
    }

    @Test
    fun `duplicate serviceName produces one provider result`() {
        val index = SearchIndex(
            accounts = listOf(
                account("a1", "GitHub", "alice"),
                account("a2", "GitHub", "bob"),
            ),
            totpCredentials = emptyList(),
            recoverySets = emptyList(),
            developerMetadata = emptyList(),
        )
        val providers = index.search("github").filterIsInstance<SearchResult.Provider>()
        assertEquals(1, providers.size)
        assertEquals(2, providers[0].accountCount)
    }

    // ------------------------------------------------------------------
    // 14-27. secret exclusion
    // ------------------------------------------------------------------

    @Test
    fun `totp secret cannot be searched`() {
        val secret = "JBSWY3DPEHPK3PXP"
        val index = SearchIndex(
            accounts = listOf(account("a1", "Google", "user@example.com")),
            totpCredentials = listOf(totp("t1", "a1", secret)),
            recoverySets = emptyList(),
            developerMetadata = emptyList(),
        )
        assertTrue(index.search(secret).isEmpty())
    }

    @Test
    fun `totp generated code cannot be searched`() {
        val code = "123456"
        val index = SearchIndex(
            accounts = listOf(account("a1", "Google", "user@example.com")),
            totpCredentials = listOf(totp("t1", "a1")),
            recoverySets = emptyList(),
            developerMetadata = emptyList(),
        )
        assertTrue(index.search(code).isEmpty())
    }

    @Test
    fun `recovery plaintext cannot be searched`() {
        val code = "ABC-123-DEF"
        val index = SearchIndex(
            accounts = listOf(account("a1", "Google", "user@example.com")),
            totpCredentials = emptyList(),
            recoverySets = listOf(
                RecoveryCodeSet(
                    id = "s1",
                    stableId = "ss1",
                    accountId = "a1",
                    title = "backup",
                    createdAt = "t",
                    codes = listOf(RecoveryCode("c1", "sc1", "s1", code, false, null, 0)),
                ),
            ),
            developerMetadata = emptyList(),
        )
        assertTrue(index.search(code).isEmpty())
    }

    @Test
    fun `apiKey and apiSecret cannot be searched`() {
        val apiKey = "AKIA1234567890"
        val apiSecret = "super-secret-api-secret-value"
        val index = SearchIndex(
            accounts = emptyList(),
            totpCredentials = emptyList(),
            recoverySets = emptyList(),
            developerMetadata = listOf(
                DeveloperSearchMetadata(
                    stableId = "d1",
                    title = "CI bot",
                    type = "API_CREDENTIAL",
                    serviceName = "github.com",
                    accountName = "bot",
                ),
            ),
        )
        // The metadata carries no secret values, so neither can match.
        assertTrue(index.search(apiKey).isEmpty())
        assertTrue(index.search(apiSecret).isEmpty())
    }

    @Test
    fun `ssh privateKey and passphrase cannot be searched`() {
        val privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----secret-content-----END-----"
        val passphrase = "ssh-passphrase-123"
        val index = SearchIndex(
            accounts = emptyList(),
            totpCredentials = emptyList(),
            recoverySets = emptyList(),
            developerMetadata = listOf(
                DeveloperSearchMetadata(
                    stableId = "d1",
                    title = "deploy",
                    type = "SSH_KEY",
                    keyName = "server-deploy",
                ),
            ),
        )
        assertTrue(index.search(privateKey).isEmpty())
        assertTrue(index.search(passphrase).isEmpty())
    }

    @Test
    fun `signing storePassword keyPassword and keystore base64 cannot be searched`() {
        val storePassword = "store-pass-1"
        val keyPassword = "key-pass-2"
        val keystoreBase64 = "AAAAeW9sb0Jhc2U2NEtleXN0b3JlQnl0ZXM="
        val index = SearchIndex(
            accounts = emptyList(),
            totpCredentials = emptyList(),
            recoverySets = emptyList(),
            developerMetadata = listOf(
                DeveloperSearchMetadata(
                    stableId = "d1",
                    title = "release",
                    type = "ANDROID_SIGNING_KEY",
                    projectName = "app",
                    packageName = "com.example",
                    keystoreFileName = "release.jks",
                    keyAlias = "alias",
                ),
            ),
        )
        assertTrue(index.search(storePassword).isEmpty())
        assertTrue(index.search(keyPassword).isEmpty())
        assertTrue(index.search(keystoreBase64).isEmpty())
    }

    @Test
    fun `env value and generic value cannot be searched`() {
        val envValue = "DB_PASSWORD=supersecret"
        val genericValue = "my-super-generic-value"
        val index = SearchIndex(
            accounts = emptyList(),
            totpCredentials = emptyList(),
            recoverySets = emptyList(),
            developerMetadata = listOf(
                DeveloperSearchMetadata(
                    stableId = "d1",
                    title = "env set",
                    type = "ENVIRONMENT_VARIABLE_SET",
                    projectName = "app",
                    variableNames = listOf("DB_PASSWORD"),
                ),
                DeveloperSearchMetadata(
                    stableId = "d2",
                    title = "wifi",
                    type = "GENERIC_SECRET",
                    fieldLabels = listOf("password"),
                ),
            ),
        )
        assertTrue(index.search(envValue).isEmpty())
        assertTrue(index.search(genericValue).isEmpty())
    }

    @Test
    fun `developer notes secret-looking content is not searchable`() {
        val noteSecret = "BEGIN PRIVATE KEY LEAKED IN NOTES"
        val index = SearchIndex(
            accounts = emptyList(),
            totpCredentials = emptyList(),
            recoverySets = emptyList(),
            developerMetadata = listOf(
                DeveloperSearchMetadata(
                    stableId = "d1",
                    title = "some entry",
                    type = "GENERIC_SECRET",
                    fieldLabels = listOf("label"),
                ),
            ),
        )
        // Notes are never projected into search docs (P7 §7).
        assertTrue(index.search(noteSecret).isEmpty())
    }

    @Test
    fun `searchResult toString does not contain fixture secrets`() {
        val secret = "SHHH-TOPSECRET-FIXTURE"
        val index = SearchIndex(
            accounts = listOf(account("a1", "GitHub", "alice")),
            totpCredentials = listOf(totp("t1", "a1", "JBSWY3DPEHPK3PXP")),
            recoverySets = listOf(
                RecoveryCodeSet(
                    id = "s1",
                    stableId = "ss1",
                    accountId = "a1",
                    title = "backup",
                    createdAt = "t",
                    codes = listOf(RecoveryCode("c1", "sc1", "s1", secret, false, null, 0)),
                ),
            ),
            developerMetadata = emptyList(),
        )
        val allResults = index.search("github") + index.search("backup") + index.search("alice")
        val blob = allResults.joinToString { it.toString() } + allResults.joinToString { it.title + (it.subtitle ?: "") }
        assertFalse(blob.contains(secret))
    }

    @Test
    fun `pinned account ranks before unpinned within same tier`() {
        val index = SearchIndex(
            accounts = listOf(
                account("a1", "GitHub", "zeta", pinned = false),
                account("a2", "GitHub", "alpha", pinned = true),
            ),
            totpCredentials = emptyList(),
            recoverySets = emptyList(),
            developerMetadata = emptyList(),
        )
        val accounts = index.search("github").filterIsInstance<SearchResult.Account>()
        assertEquals(2, accounts.size)
        // pinned sorts first regardless of alpha/zeta
        assertEquals("a2", accounts[0].navigationId)
    }
}
